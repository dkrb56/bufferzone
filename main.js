// Main process: window, settings storage, token storage, and a Plex HTTP proxy
// so the renderer never has to deal with CORS for JSON API calls.
const { app, BrowserWindow, ipcMain, shell, safeStorage, session } = require('electron');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');

const userDir = app.getPath('userData');
const settingsFile = path.join(userDir, 'settings.json');
const tokenFile = path.join(userDir, 'token.bin');
const logFile = path.join(userDir, 'buffer-zone.log');

function log(...args) {
  const line = `[${new Date().toISOString()}] ${args.map(a => (typeof a === 'string' ? a : JSON.stringify(a))).join(' ')}\n`;
  try { fs.appendFileSync(logFile, line); } catch {}
}

const defaultSettings = {
  clientId: null,
  serverId: null,
  quality: 'direct',           // see QUALITY in renderer/js/player.js
  audioLang: 'eng',
  subtitleLang: 'eng',
  subtitleMode: 'auto',        // off | auto (only when audio is foreign / forced) | always
  subtitleSize: 100,           // percent
  subtitleColor: '#ffffff',
  subtitleBackground: 'shadow', // shadow | box | none
  subtitlePosition: 8,         // % from bottom
  audioBoost: 100,             // percent; above 100 the player amplifies through Web Audio
  autoSkipIntro: false,
  autoSkipCredits: false,
  autoPlayNext: true,
  nextCountdown: 10,           // seconds
  hiddenLibraries: [],
  seriesPrefs: {},             // { [showRatingKey]: { audioLang, subtitleLang, subtitleOff } }
  pinned: [],                  // [{ key, title, type }] shown in the icon rail
  profileId: null,             // Plex Home user id of the last profile
  profileRules: {},            // { [userId]: parental rules, see DEFAULT_RULES in renderer/js/app.js }
  homeTab: 'all',
  lastLibrary: {},             // { movie|show|kids: sectionKey }
  libraryFilters: {},
  mvLayout: '2x2'
};

function readSettings() {
  let s = {};
  try { s = JSON.parse(fs.readFileSync(settingsFile, 'utf8')); } catch {}
  s = { ...defaultSettings, ...s };
  if (!s.clientId) {
    s.clientId = crypto.randomUUID();
    writeSettings(s);
  }
  return s;
}

function writeSettings(s) {
  fs.mkdirSync(userDir, { recursive: true });
  fs.writeFileSync(settingsFile, JSON.stringify(s, null, 2));
}

ipcMain.handle('settings:get', () => readSettings());
ipcMain.handle('settings:set', (_e, patch) => {
  const s = { ...readSettings(), ...patch };
  writeSettings(s);
  return s;
});

ipcMain.handle('token:get', () => {
  try {
    const buf = fs.readFileSync(tokenFile);
    return safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(buf) : buf.toString('utf8');
  } catch { return null; }
});
ipcMain.handle('token:set', (_e, token) => {
  if (!token) { try { fs.unlinkSync(tokenFile); } catch {} return true; }
  const buf = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(token) : Buffer.from(token, 'utf8');
  fs.writeFileSync(tokenFile, buf);
  return true;
});

ipcMain.handle('open-external', (_e, url) => {
  if (/^https:\/\/(app\.)?plex\.tv\//.test(url) || /^https:\/\/github\.com\/dkrb56\/bufferzone\//.test(url)) shell.openExternal(url);
});

ipcMain.handle('log', (_e, ...args) => log('[renderer]', ...args));
ipcMain.on('app:version', (e) => { e.returnValue = app.getVersion(); });

// Generic HTTP proxy. Returns { ok, status, body } where body is parsed JSON when possible.
ipcMain.handle('http', async (_e, { url, method = 'GET', headers = {}, timeout = 15000 }) => {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeout);
  try {
    const res = await fetch(url, { method, headers, signal: ctrl.signal });
    const text = await res.text();
    let body = text;
    try { body = JSON.parse(text); } catch {}
    if (!res.ok) log('http', method, redact(url), res.status);
    return { ok: res.ok, status: res.status, body };
  } catch (err) {
    log('http error', method, redact(url), String(err));
    return { ok: false, status: 0, body: String(err) };
  } finally {
    clearTimeout(timer);
  }
});

function redact(url) {
  return String(url).replace(/(X-Plex-Token=)[^&]+/gi, '$1***');
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1400,
    height: 860,
    minWidth: 960,
    minHeight: 600,
    backgroundColor: '#050505',
    autoHideMenuBar: true,
    title: 'Buffer Zone',
    icon: path.join(__dirname, 'build', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.webContents.on('console-message', (e) => {
    if (e.level === 'error' || e.level === 'warning') log('[console]', e.level, e.message);
  });
  win.webContents.on('render-process-gone', (_e, d) => log('renderer gone', d));
  // F11 fullscreen, Ctrl+Shift+I devtools
  win.webContents.on('before-input-event', (_e, input) => {
    if (input.type !== 'keyDown') return;
    if (input.key === 'F12' || (input.control && input.shift && input.key.toLowerCase() === 'i')) win.webContents.toggleDevTools();
  });
  ipcMain.removeHandler('fullscreen');
  ipcMain.handle('fullscreen', (_e, on) => { win.setFullScreen(on === undefined ? !win.isFullScreen() : !!on); return win.isFullScreen(); });
}

app.whenReady().then(() => {
  // Media and HLS segments are loaded by the renderer straight from the Plex server.
  // Some servers omit CORS headers on those responses, so add them for our window only.
  session.defaultSession.webRequest.onHeadersReceived((details, cb) => {
    const h = details.responseHeaders || {};
    const has = Object.keys(h).some(k => k.toLowerCase() === 'access-control-allow-origin');
    if (!has && /^https?:/.test(details.url)) h['Access-Control-Allow-Origin'] = ['*'];
    cb({ responseHeaders: h });
  });
  log('start', app.getVersion(), process.versions.electron);
  createWindow();
});

app.on('window-all-closed', () => app.quit());
