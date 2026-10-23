const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('sofa', {
  http: (req) => ipcRenderer.invoke('http', req),
  getSettings: () => ipcRenderer.invoke('settings:get'),
  setSettings: (patch) => ipcRenderer.invoke('settings:set', patch),
  getToken: () => ipcRenderer.invoke('token:get'),
  setToken: (t) => ipcRenderer.invoke('token:set', t),
  openExternal: (url) => ipcRenderer.invoke('open-external', url),
  fullscreen: (on) => ipcRenderer.invoke('fullscreen', on),
  log: (...args) => ipcRenderer.invoke('log', ...args)
});
