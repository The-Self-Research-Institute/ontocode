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
// Use synchronous IPC to get the proxy port BEFORE React initializes.
// This ensures WebSocket connects to the correct URL on first render.
let _cfg;
try { _cfg = ipcRenderer.sendSync('config:get-sync'); } catch (_) {}
const PROXY_API = (_cfg && _cfg.apiBaseUrl) || 'http://127.0.0.1:18085';
const DESKTOP_API = PROXY_API; // use proxy so /api/swrl/** routes work too

window.__ONTOCODE_CONFIG__ = {
    IS_WEB_EXTENSION: true,
    IS_DESKTOP: true,
    CLOUD_GATEWAY_URL: DESKTOP_API,
    SELF_HOSTED_GATEWAY_URL: DESKTOP_API,
    DESKTOP_API_URL: DESKTOP_API,
};
window.__DESKTOP_API_URL__ = DESKTOP_API;
window.__DESKTOP_MODE__ = true;

// Ensure the React app skips DeploymentSelector on first launch.
try {
    if (!localStorage.getItem('deploymentType')) {
        localStorage.setItem('deploymentType', 'self-hosted');
    }
} catch { /* sandboxed context — ignore */ }

// ── Import status poller ──────────────────────────────────────────────────────
// WebSocket STOMP may not connect in all Electron contexts. Poll the backend
// queue status endpoint and dispatch importStatusUpdate events so the spinner
// clears when an import finishes.
const _importPollers = new Map(); // projectId → intervalId

async function getAuthToken() {
    // Try IPC first (electron-store, encrypted), fallback to localStorage
    try { const t = await ipcRenderer.invoke('auth:get'); if (t) return t; } catch (_) {}
    try { return localStorage.getItem('authToken'); } catch (_) { return null; }
}

function dispatchImportCompleted(projectId) {
    window.dispatchEvent(new CustomEvent('importStatusUpdate', {
        detail: { type: 'IMPORT_COMPLETED', status: 'COMPLETED', projectId, progress: 100 }
    }));
}

async function checkImportStatus(projectId) {
    const headers = { 'Content-Type': 'application/json' };
    const t = await getAuthToken();
    if (t) headers['Authorization'] = `Bearer ${t}`;

    // Check 1: import queue status
    try {
        const res = await fetch(`${DESKTOP_API}/api/import-queue/status/${encodeURIComponent(projectId)}`, { headers });
        if (res.ok) {
            const data = await res.json();
            const status = data.status || 'NOT_IN_QUEUE';
            if (status === 'COMPLETED' || status === 'NOT_IN_QUEUE') return 'done';
            if (status === 'FAILED') return 'failed';
            // Return progress data so the loading dialog can show it
            return { state: 'progress', progress: data.progress, message: data.statusMessage };
        }
    } catch (_) {}

    // Check 2: OWLAPI cache — if model is cached, import is definitely done
    try {
        const res2 = await fetch(`${DESKTOP_API}/api/ontology/cache-status/${encodeURIComponent(projectId)}`, { headers });
        if (res2.ok) {
            const data2 = await res2.json();
            if (data2.owlapiReady) return 'done';
        }
    } catch (_) {}

    // Check 3: hierarchy data available — if top-level classes load, Fuseki has the data
    try {
        const res3 = await fetch(
            `${DESKTOP_API}/api/ontology/classes/top-level/${encodeURIComponent(projectId)}?limit=5`,
            { headers }
        );
        if (res3.ok) {
            const data3 = await res3.json();
            // If we got any classes back, import is done
            const classes = data3.classes || data3;
            if (Array.isArray(classes) && classes.length > 0) return 'done';
        }
    } catch (_) {}

    return 'unknown';
}

function startImportPoller(projectId) {
    if (_importPollers.has(projectId)) return;

    // Immediate first check — no delay
    checkImportStatus(projectId).then(result => {
        if (result === 'done') { dispatchImportCompleted(projectId); return; }
        if (result === 'failed') {
            window.dispatchEvent(new CustomEvent('importStatusUpdate', {
                detail: { type: 'IMPORT_FAILED', status: 'FAILED', projectId }
            }));
            return;
        }
        // Still in progress — start polling
        schedulePoller(projectId);
    });
}

function schedulePoller(projectId) {
    if (_importPollers.has(projectId)) return;
    let ticks = 0;

    const poll = setInterval(async () => {
        try {
            ticks++;
            const result = await checkImportStatus(projectId);

            if (result === 'done') {
                dispatchImportCompleted(projectId);
                clearInterval(poll);
                _importPollers.delete(projectId);
            } else if (result === 'failed') {
                window.dispatchEvent(new CustomEvent('importStatusUpdate', {
                    detail: { type: 'IMPORT_FAILED', status: 'FAILED', projectId }
                }));
                clearInterval(poll);
                _importPollers.delete(projectId);
            } else if (result && typeof result === 'object' && result.state === 'progress') {
                // Forward real progress data to the loading dialog
                window.dispatchEvent(new CustomEvent('importStatusUpdate', {
                    detail: {
                        type: 'IMPORT_PROGRESS', status: 'PROCESSING', projectId,
                        progress: result.progress || 0,
                        metadata: { message: result.message || 'Importing…' }
                    }
                }));
            } else if (ticks > 120) {
                // 120 × 1.5s = 3 min absolute max — force dismiss regardless
                dispatchImportCompleted(projectId);
                clearInterval(poll);
                _importPollers.delete(projectId);
            }
        } catch (_) {}
    }, 1500);

    _importPollers.set(projectId, poll);
}

// ── Global import watcher ─────────────────────────────────────────────────────
// The Dashboard sets "Pending import" state after upload-by-file-ref but never
// sends getQueueStatus to the preload, so the poller never starts.
// This watcher checks the import queue every 5 seconds and auto-starts pollers
// for any active imports — no message from Dashboard needed.
(function startGlobalImportWatcher() {
    setInterval(async () => {
        try {
            // Only run if page is visible
            if (document.hidden) return;
            const headers = { 'Content-Type': 'application/json' };
            const t = await getAuthToken();
            if (!t) return;
            headers['Authorization'] = `Bearer ${t}`;
            const res = await fetch(`${DESKTOP_API}/api/import-queue/stats`, { headers });
            if (!res.ok) return;
            const data = await res.json();
            // Start pollers for actively importing projects
            const active = data.activeProjectIds || [];
            active.forEach(pid => { if (pid) startImportPoller(pid); });
            // Also start pollers for queued projects
            const queued = data.queue || [];
            queued.forEach(item => { if (item.projectId) startImportPoller(item.projectId); });
        } catch (_) {}
    }, 5000);
})();

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
                // Start import poller so spinner clears when backend finishes
                if (message.projectId) startImportPoller(message.projectId);
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
                // Start polling the real backend; fallback response while poll runs
                if (message.projectId) startImportPoller(message.projectId);
                window.dispatchEvent(new MessageEvent('message', {
                    data: {
                        type: 'queueStatusUpdate',
                        status: { projectId: message.projectId, status: 'IN_PROGRESS', position: 1 },
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

// ── Desktop-specific API bridge ───────────────────────────────────────────────
contextBridge.exposeInMainWorld('electronAPI', {
    // File operations
    openFile:  ()                    => ipcRenderer.invoke('file:open'),
    saveAs:    (content, name)       => ipcRenderer.invoke('file:saveAs', { content, defaultName: name }),

    // Local profile (name only — no account required)
    getProfile: ()                   => ipcRenderer.invoke('profile:get'),
    saveProfile: (profile)           => ipcRenderer.invoke('profile:save', profile),

    // Runtime config (API base URL, etc.)
    getConfig:  ()                   => ipcRenderer.invoke('config:get'),

    // Service health
    getServiceStatus: ()             => ipcRenderer.invoke('services:status'),

    // Open log folder in file manager
    openLogs: ()                     => ipcRenderer.invoke('logs:open'),

    // Listen for file-open from the native menu (File → Open Ontology File…)
    onMenuOpenFile: (callback)       => ipcRenderer.on('menu:open-file', (_evt, data) => callback(data)),

    // License management
    getLicense:      ()              => ipcRenderer.invoke('license:get'),
    importLicense:   (json)          => ipcRenderer.invoke('license:import', json),
    openPurchase:    (plan)          => ipcRenderer.invoke('license:openPurchase', plan),

    // Sync / Syncthing
    syncStatus:      ()              => ipcRenderer.invoke('sync:status'),
    syncDeviceId:    ()              => ipcRenderer.invoke('sync:deviceId'),
    syncFolders:     ()              => ipcRenderer.invoke('sync:folders'),
    syncShareWorkspace: (label)      => ipcRenderer.invoke('sync:shareWorkspace', { label }),
    syncGenerateLink:   (folderIds)  => ipcRenderer.invoke('sync:generateLink', { folderIds }),
    syncParseLink:      (link)       => ipcRenderer.invoke('sync:parseLink', { link }),
    syncAddPeer:        (opts)       => ipcRenderer.invoke('sync:addPeer', opts),
    syncCompletion:     (opts)       => ipcRenderer.invoke('sync:completion', opts),

    // Start polling import status (used by Dashboard when WebSocket is unavailable)
    pollImportStatus: (projectId)    => startImportPoller(projectId),
});
