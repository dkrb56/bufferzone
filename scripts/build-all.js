// Builds every platform into dist/ with the version from package.json:
//   BufferZone-<v>-portable.exe          Windows
//   BufferZone-tv-<v>.apk                Android TV
//   com.dkrb56.bufferzone_<v>_all.ipk    LG webOS
// Usage: npm run build:all
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const DIST = path.join(ROOT, 'dist');
const VERSION = require(path.join(ROOT, 'package.json')).version;
const JDK = process.env.JAVA_HOME || 'C:\\Program Files\\Android\\Android Studio\\jbr';
const run = (cmd, cwd, env) => execSync(cmd, { cwd, stdio: 'inherit', env: { ...process.env, ...env } });

console.log(`\n== Buffer Zone ${VERSION}: Windows`);
run('npx electron-builder --win portable', ROOT);

console.log('\n== Android TV');
run(`"${path.join(ROOT, 'android', process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')}" assembleDebug`, path.join(ROOT, 'android'), { JAVA_HOME: JDK });
fs.copyFileSync(path.join(ROOT, 'android', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'), path.join(DIST, `BufferZone-tv-${VERSION}.apk`));

console.log('\n== LG webOS');
run('node build.js', path.join(ROOT, 'webos'));

const files = [`BufferZone-${VERSION}-portable.exe`, `BufferZone-tv-${VERSION}.apk`, `com.dkrb56.bufferzone_${VERSION}_all.ipk`];
for (const f of files) {
  if (!fs.existsSync(path.join(DIST, f))) throw new Error('missing ' + f);
  console.log(`${f}  ${(fs.statSync(path.join(DIST, f)).size / 1048576).toFixed(1)} MB`);
}
