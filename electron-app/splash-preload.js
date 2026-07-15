/**
 * Preload for the splash window.
 * Exposes window.splashBridge.onLog(callback) so splash.html can
 * receive service-log IPC events from the main process.
 */
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('splashBridge', {
    onLog: (callback) => {
        ipcRenderer.on('service-log', (_event, { level, msg }) => callback(level, msg));
    },
});
