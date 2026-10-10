'use strict';

const { contextBridge, ipcRenderer, webUtils } = require('electron');

// The renderer gets exactly these calls and nothing else - no direct fs or
// node access from the page.
contextBridge.exposeInMainWorld('labunzip', {
  startupArchive: () => ipcRenderer.invoke('startup-archive'),
  pickArchive: () => ipcRenderer.invoke('pick-archive'),
  pickDestination: (current) => ipcRenderer.invoke('pick-destination', current),
  scan: (zipPath) => ipcRenderer.invoke('scan', zipPath),
  buildPlan: (payload) => ipcRenderer.invoke('build-plan', payload),
  run: (payload) => ipcRenderer.invoke('run', payload),
  cancel: () => ipcRenderer.invoke('cancel'),
  reveal: (target) => ipcRenderer.invoke('reveal', target),

  // Dropped files no longer expose .path directly; this is the supported way
  // to get a real path out of a File in current Electron.
  pathForFile: (file) => {
    try {
      return webUtils.getPathForFile(file);
    } catch {
      return null;
    }
  },

  onScanProgress: (handler) => ipcRenderer.on('scan-progress', (_e, data) => handler(data)),
  onRunProgress: (handler) => ipcRenderer.on('run-progress', (_e, data) => handler(data)),
  onOpenArchive: (handler) => ipcRenderer.on('open-archive', (_e, data) => handler(data)),
});
