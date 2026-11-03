const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('sofa', {
  version: ipcRenderer.sendSync('app:version'),
  canSelfUpdate: ipcRenderer.sendSync('update:can'),
  downloadUpdate: () => ipcRenderer.invoke('update:download'),
  installUpdate: () => ipcRenderer.invoke('update:install'),
  onUpdateProgress: (cb) => { ipcRenderer.removeAllListeners('update:progress'); ipcRenderer.on('update:progress', (_e, pct) => cb(pct)); },
  http: (req) => ipcRenderer.invoke('http', req),
  getSettings: () => ipcRenderer.invoke('settings:get'),
  setSettings: (patch) => ipcRenderer.invoke('settings:set', patch),
  getToken: () => ipcRenderer.invoke('token:get'),
  setToken: (t) => ipcRenderer.invoke('token:set', t),
  openExternal: (url) => ipcRenderer.invoke('open-external', url),
  fullscreen: (on) => ipcRenderer.invoke('fullscreen', on),
  log: (...args) => ipcRenderer.invoke('log', ...args)
});
