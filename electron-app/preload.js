/**
 * Electron preload script – OntoCode Desktop
 *
 * Runs in a sandboxed renderer context (contextIsolation: true).
 * Exposes a window.vscode-compatible postMessage API so the React app
 * works without any code changes.
 *
 * Also injects __ONTOCODE_CONFIG__ with IS_WEB_EXTENSION: true so that
 * apiClient.ts uses the direct axios path (no VS Code extension proxy needed).
 *
 * Message types handled:
 *   webviewReady             → no-op (app is already loaded)
 *   requestAuthToken         → read from electron-store, reply as 'storedAuthToken'
 *   saveAuthToken            → persist to electron-store
 *   logout                   → clear from electron-store
 *   showNotification         → native OS notification via main process
 *   requestCollaborationStatus → no-op (collaboration uses direct WebSocket)
 *   setApiBaseUrl            → update localStorage (apiClient reads it)
 *   uploadOntology /
 *   uploadFileToProject      → handled by React app in browser mode (apiClient axios)
 *
 * Note: because IS_WEB_EXTENSION is true, apiClient.ts uses axios directly
 * for all API calls.  Only auth and file-open need IPC.
 */

const { contextBridge, ipcRenderer } = require('electron');

// ── Inject runtime config ────────────────────────────────────────────────────
// Tell the React build to use direct axios + localStorage (web extension mode).
// The cloud gateway URL can be overridden via the DeploymentSelector in-app.
window.__ONTOCODE_CONFIG__ = {
    IS_WEB_EXTENSION: true,
    CLOUD_GATEWAY_URL: process.env.CLOUD_GATEWAY_URL || 'https://ontocodeapi.selfresearch.org',
    SELF_HOSTED_GATEWAY_URL: process.env.SELF_HOSTED_GATEWAY_URL || 'http://localhost:80',
};

// Prevent the browser bridge (vscodeBridge.ts) from overwriting our IPC-based
// window.vscode — the Electron preload's version uses native dialogs and
// encrypted token storage which are superior to the browser fallbacks.
window.__ONTOCODE_BROWSER_BRIDGE__ = true;

// ── window.vscode bridge ─────────────────────────────────────────────────────
contextBridge.exposeInMainWorld('vscode', {
    postMessage: async (message) => {
        switch (message.type) {

            case 'webviewReady':
                // App is open; send back any stored token so AuthContext initialises
                try {
                    const token = await ipcRenderer.invoke('auth:get');
                    window.dispatchEvent(new MessageEvent('message', {
                        data: { type: 'storedAuthToken', token: token || null }
                    }));
                } catch (err) {
                    console.error('[Preload] auth:get failed', err);
                }
                break;

            case 'requestAuthToken':
                try {
                    const token = await ipcRenderer.invoke('auth:get');
                    window.dispatchEvent(new MessageEvent('message', {
                        data: { type: 'storedAuthToken', token: token || null }
                    }));
                } catch (err) {
                    console.error('[Preload] auth:get failed', err);
                }
                break;

            case 'saveAuthToken':
                ipcRenderer.invoke('auth:save', message.token).catch(console.error);
                break;

            case 'logout':
                ipcRenderer.invoke('auth:clear').catch(console.error);
                window.dispatchEvent(new MessageEvent('message', { data: { type: 'loggedOut' } }));
                break;

            case 'showNotification':
                ipcRenderer.invoke('notification:show', message.notification).catch(console.error);
                break;

            case 'error':
                ipcRenderer.invoke('notification:show', {
                    title: 'Error',
                    message: message.value || 'Unknown error',
                    type: 'error',
                }).catch(console.error);
                break;

            case 'notification':
                ipcRenderer.invoke('notification:show', {
                    title: message.level || 'info',
                    message: message.message || '',
                    type: message.level || 'info',
                }).catch(console.error);
                break;

            case 'requestCollaborationStatus':
                window.dispatchEvent(new MessageEvent('message', {
                    data: { type: 'collaborationStatus', connected: false }
                }));
                break;

            case 'setApiBaseUrl':
                if (message.deploymentType) {
                    localStorage.setItem('deploymentType', message.deploymentType);
                }
                break;

            case 'fileLoaded':
                // Extension initialises collaboration WebSocket; no-op in Electron.
                break;

            case 'openLocalFile':
            case 'importLocalFile':
                // Use native file dialog via main process
                (async () => {
                    try {
                        const result = await ipcRenderer.invoke('file:open');
                        if (result) {
                            window.dispatchEvent(new MessageEvent('message', {
                                data: {
                                    type: 'pendingFileUpload',
                                    fileName: result.fileName,
                                    fileContent: result.fileContent, // base64 from main
                                    fileSize: result.fileSize,
                                    importMode: message.importMode,
                                    partition: message.partition,
                                }
                            }));
                        }
                    } catch (err) {
                        console.error('[Preload] file:open failed', err);
                    }
                })();
                break;

            case 'downloadFile': {
                // Trigger browser blob download
                const blob = new Blob([message.content], { type: 'application/octet-stream' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = message.filename;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
                break;
            }

            case 'downloadOntology':
            case 'downloadCurrentOntology':
                // Handled by React app via apiClient (IS_WEB_EXTENSION is true)
                console.log('[Preload] Download message – handled by React browser-mode:', message.type);
                break;

            case 'uploadOntology':
            case 'uploadFileToProject':
            case 'uploadOntologyContent':
                console.log('[Preload] Upload message – handled by React browser-mode:', message.type);
                break;

            case 'cursorMoved':
            case 'broadcastCursor':
                // Collaboration cursor — no-op in standalone desktop
                break;

            case 'requestZoteroLibrary':
            case 'insertCitation':
            case 'insertManualCitation':
            case 'insertCitationToGraphDB':
            case 'removeCitationFromGraphDB':
                // Citation operations — handled by React app via apiClient
                console.log('[Preload] Citation message – handled by React browser-mode:', message.type);
                break;

            case 'showSubscriptionPlans':
                window.dispatchEvent(new MessageEvent('message', { data: { type: 'showSubscriptionPlans' } }));
                break;

            case 'duplicateFilePromptResponse':
                break;

            case 'getQueueStatus':
                window.dispatchEvent(new MessageEvent('message', {
                    data: {
                        type: 'queueStatusUpdate',
                        status: { projectId: message.projectId, status: 'COMPLETED', position: 0 },
                    }
                }));
                break;

            case 'apiGet':
            case 'apiPost':
            case 'apiPut':
            case 'apiPatch':
            case 'apiDelete':
            case 'proxyRequest':
                // apiClient uses direct axios in IS_WEB_EXTENSION mode
                break;

            default:
                console.log('[Preload] Unhandled postMessage type:', message.type);
        }
    }
});

// ── Expose a native file-open helper so App.tsx can trigger native dialog ───
// This augments window.vscode; the React app can call window.electronAPI.openFile()
// for the native picker instead of the File System Access API.
contextBridge.exposeInMainWorld('electronAPI', {
    openFile: () => ipcRenderer.invoke('file:open'),
});
