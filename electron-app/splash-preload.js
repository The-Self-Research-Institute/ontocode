
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('splashBridge', {
    onLog: (callback) => {
        ipcRenderer.on('service-log', (_event, { level, msg }) => callback(level, msg));
    },
    openLogs: () => ipcRenderer.invoke('logs:open'),
    quit: () => ipcRenderer.invoke('splash:quit'),
});
