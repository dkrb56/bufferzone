// Builds the LG webOS package from ../renderer:
//   webos/build/app            the web app (shared renderer + shim + remote navigation)
//   webos/service              packed as is: the HTTP helper service
//   dist/com.dkrb56.bufferzone_<version>_all.ipk
// The JS is lowered to Chromium 68 (webOS 5, 2020 TVs); the CSS gets fallbacks for the same browsers.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const esbuild = require('esbuild');

const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(__dirname, 'build', 'app');
const DIST = path.join(ROOT, 'dist');
const VERSION = require(path.join(ROOT, 'package.json')).version;
const TARGET = 'chrome68';

function copyDir(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    const a = path.join(from, e.name), b = path.join(to, e.name);
    if (e.isDirectory()) copyDir(a, b); else fs.copyFileSync(a, b);
  }
}

// ---------- CSS fallbacks ----------
// Split "a, b(c, d), e" at the top-level commas.
function splitTop(s) {
  const out = []; let depth = 0, cur = '';
  for (const ch of s) {
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (ch === ',' && depth === 0) { out.push(cur.trim()); cur = ''; } else cur += ch;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}
// Top-level rules as { sel, body }; at-rules (@keyframes) are skipped.
function rules(css) {
  const out = []; let i = 0;
  css = css.replace(/\/\*[\s\S]*?\*\//g, '');
  while (i < css.length) {
    const open = css.indexOf('{', i);
    if (open < 0) break;
    const sel = css.slice(i, open).trim();
    let depth = 1, j = open + 1;
    while (j < css.length && depth) { if (css[j] === '{') depth++; else if (css[j] === '}') depth--; j++; }
    if (!sel.startsWith('@')) out.push({ sel, body: css.slice(open + 1, j - 1) });
    i = j;
  }
  return out;
}
function decls(body) {
  const out = {};
  for (const d of body.split(';')) {
    const k = d.indexOf(':');
    if (k > 0) out[d.slice(0, k).trim()] = d.slice(k + 1).trim();
  }
  return out;
}

function fixCss(css) {
  // inset (Chromium 87) -> longhands
  css = css.replace(/(^|[;{\s])inset:\s*0\s*;/g, '$1top: 0; right: 0; bottom: 0; left: 0;');

  const gapRules = [], minMax = [];
  for (const { sel, body } of rules(css)) {
    const d = decls(body);
    const sels = splitTop(sel);
    // flex gap (Chromium 84) -> margins, only when the shim finds no flex gap support
    if (/(^|\s)(inline-)?flex/.test(d.display || '') && d.gap) {
      const [rowGap, colGap = rowGap] = d.gap.split(/\s+/);
      const column = /column/.test(d['flex-direction'] || '');
      const wrap = /wrap/.test(d['flex-wrap'] || '') && !/nowrap/.test(d['flex-wrap']);
      const pre = sels.map(s => '.no-flexgap ' + s);
      if (wrap) gapRules.push(`${pre.map(s => s + ' > *').join(', ')} { margin-right: ${colGap}; margin-bottom: ${rowGap}; }`);
      else if (column) gapRules.push(`${pre.map(s => s + ' > * + *').join(', ')} { margin-top: ${rowGap}; }`);
      else gapRules.push(`${pre.map(s => s + ' > * + *').join(', ')} { margin-left: ${colGap}; }`);
    }
    // min()/max() (Chromium 79): min -> first value, max -> last value
    for (const [prop, val] of Object.entries(d)) {
      const m = val.match(/^(min|max)\((.*)\)$/);
      if (!m) continue;
      const args = splitTop(m[2]);
      minMax.push(`${sel} { ${prop}: ${m[1] === 'min' ? args[0] : args[args.length - 1]}; }`);
    }
  }
  return css +
    '\n/* ---------- added by webos/build.js ---------- */\n' +
    gapRules.join('\n') + '\n' +
    `@supports not (width: min(1px, 2px)) {\n${minMax.join('\n')}\n}\n`;
}

// ---------- icons (Electron's nativeImage resizes the PNG) ----------
function makeIcons() {
  const script = path.join(__dirname, 'build', 'resize-icons.js');
  fs.writeFileSync(script, `
    const { app, nativeImage } = require('electron');
    const fs = require('fs');
    app.whenReady().then(() => {
      const src = nativeImage.createFromPath(${JSON.stringify(path.join(ROOT, 'build', 'icon-512.png'))});
      for (const [file, size] of [['icon.png', 80], ['largeIcon.png', 130]]) {
        fs.writeFileSync(${JSON.stringify(OUT)} + '/' + file, src.resize({ width: size, height: size, quality: 'best' }).toPNG());
      }
      app.quit();
    });`);
  execFileSync(require(path.join(ROOT, 'node_modules', 'electron')), [script], { stdio: 'inherit' });
  fs.unlinkSync(script);
}

async function main() {
  fs.rmSync(path.join(__dirname, 'build'), { recursive: true, force: true });
  copyDir(path.join(ROOT, 'renderer'), OUT);
  fs.copyFileSync(path.join(ROOT, 'node_modules', 'hls.js', 'dist', 'hls.min.js'), path.join(OUT, 'js', 'hls.min.js'));
  for (const f of ['shim.js', 'tvnav.js', 'webos.css']) {
    fs.copyFileSync(path.join(__dirname, 'src', f), path.join(OUT, f.endsWith('.css') ? f : path.join('js', f)));
  }

  // index.html: local hls.js, shim first, remote navigation last, TV styles after the shared ones.
  // The CSP meta goes: the app runs from file:// and talks to plex.tv and the Plex server directly.
  let html = fs.readFileSync(path.join(OUT, 'index.html'), 'utf8');
  html = html
    .replace(/\s*<meta http-equiv="Content-Security-Policy"[\s\S]*?>/, '')
    .replace('<link rel="stylesheet" href="styles.css">', '<link rel="stylesheet" href="styles.css">\n  <link rel="stylesheet" href="webos.css">')
    .replace(/<script src="[^"]*hls\.min\.js"><\/script>/, '<script src="js/shim.js"></script>\n  <script src="js/hls.min.js"></script>')
    .replace('</body>', '  <script src="js/tvnav.js"></script>\n</body>');
  if (!html.includes('js/shim.js') || !html.includes('js/tvnav.js')) throw new Error('index.html: script tags not found');
  fs.writeFileSync(path.join(OUT, 'index.html'), html);

  // JS: lower the syntax for Chromium 68 (optional chaining, ??, class fields, …). hls.min.js stays minified.
  for (const f of fs.readdirSync(path.join(OUT, 'js'))) {
    if (!f.endsWith('.js')) continue;
    const file = path.join(OUT, 'js', f);
    const { code } = await esbuild.transform(fs.readFileSync(file, 'utf8'), { loader: 'js', target: TARGET, charset: 'utf8', minify: f === 'hls.min.js' });
    fs.writeFileSync(file, code);
  }
  for (const f of ['styles.css', 'webos.css']) {
    const file = path.join(OUT, f);
    let css = fs.readFileSync(file, 'utf8');
    if (f === 'styles.css') css = fixCss(css);
    fs.writeFileSync(file, css.replace(/(^|[;{\s])inset:\s*0\s*;/g, '$1top: 0; right: 0; bottom: 0; left: 0;'));
  }

  makeIcons();
  fs.writeFileSync(path.join(OUT, 'appinfo.json'), JSON.stringify({
    id: 'com.dkrb56.bufferzone',
    version: VERSION,
    vendor: 'dkrb56',
    type: 'web',
    main: 'index.html',
    title: 'Buffer Zone',
    icon: 'icon.png',
    largeIcon: 'largeIcon.png',
    bgColor: '#000000',
    resolution: '1920x1080',   // full HD graphics; 1280x720 is drawn small and scaled up (blurry). shim.js zooms the layout.
    disableBackHistoryAPI: true,
    requiredACG: []   // calls only its own service, no system Luna APIs (needed on webOS 27+)
  }, null, 2));

  fs.mkdirSync(DIST, { recursive: true });
  if (process.argv.includes('--no-package')) return console.log('Built', OUT);
  // The package holds the app and its HTTP helper service (webos/service).
  execFileSync(process.execPath, [path.join(__dirname, 'node_modules', '@webos-tools', 'cli', 'bin', 'ares-package.js'), OUT, path.join(__dirname, 'service'), '-o', DIST], { stdio: 'inherit' });
}

main().catch((e) => { console.error(e); process.exit(1); });
