

const { contextBridge, ipcRenderer } = require('electron');

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

try {
    if (!localStorage.getItem('deploymentType')) {
        localStorage.setItem('deploymentType', 'self-hosted');
    }
} catch { /* sandboxed context — ignore */ }

const _importPollers = new Map(); // projectId → intervalId

async function getAuthToken() {

    try { const t = await ipcRenderer.invoke('auth:get'); if (t) return t; } catch (_) {}
    try { return localStorage.getItem('authToken'); } catch (_) { return null; }
}

const EXPORT_POLL_INTERVAL_MS = 3000;
const EXPORT_MAX_POLL_MS = 60 * 60 * 1000;

// Per-request network timeout. Separate from EXPORT_MAX_POLL_MS, which bounds
// the whole export job (legitimately long for big ontologies) — this catches
// a single stalled/dead connection (e.g. dropped socket) that would otherwise
// never resolve or reject, hanging the renderer's await indefinitely.
async function fetchWithTimeout(url, options = {}, timeoutMs = 30_000) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
        return await fetch(url, { ...options, signal: controller.signal });
    } catch (err) {
        if (err.name === 'AbortError') {
            throw new Error(`Request timed out after ${Math.round(timeoutMs / 1000)}s.`);
        }
        throw err;
    } finally {
        clearTimeout(timer);
    }
}

async function submitExportJob(projectId, format) {
    const t = await getAuthToken();
    const headers = { 'Content-Type': 'application/json' };
    if (t) headers['Authorization'] = `Bearer ${t}`;
    const res = await fetchWithTimeout(`${DESKTOP_API}/api/ontology/export-async/${encodeURIComponent(projectId)}?format=${encodeURIComponent(format)}`, {
        method: 'POST',
        headers,
    });
    if (!res.ok) throw new Error(`Export could not be started (HTTP ${res.status}).`);
    const data = await res.json();
    if (!data || !data.jobId) throw new Error('Export could not be started.');
    return data.jobId;
}

async function waitForExportJob(jobId) {
    const t = await getAuthToken();
    const headers = {};
    if (t) headers['Authorization'] = `Bearer ${t}`;
    const deadline = Date.now() + EXPORT_MAX_POLL_MS;
    // eslint-disable-next-line no-constant-condition
    while (true) {
        // A single poll timing out (dropped connection, brief backend hiccup) shouldn't
        // fail the whole export — only the overall deadline below should give up.
        let data = null;
        try {
            const res = await fetchWithTimeout(`${DESKTOP_API}/api/ontology/export-async/status/${jobId}`, { headers }, 15_000);
            data = await res.json().catch(() => null);
        } catch (_) { /* transient — retry on next poll unless deadline passed */ }
        if (data && data.status === 'COMPLETED') return;
        if (data && data.status === 'ERROR') throw new Error(data.error || 'Export failed.');
        if (Date.now() >= deadline) throw new Error('Export is taking much longer than expected. Please try again later.');
        await new Promise((resolve) => setTimeout(resolve, EXPORT_POLL_INTERVAL_MS));
    }
}

function triggerBlobDownload(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

async function exportOntologyViaJob(projectId, format, filename) {
    const safeName = filename || `ontology-export.${format === 'turtle' ? 'ttl' : format === 'ntriples' ? 'nt' : 'owl'}`;

    const jobId = await submitExportJob(projectId, format);
    await waitForExportJob(jobId);

    const t = await getAuthToken();
    const headers = {};
    if (t) headers['Authorization'] = `Bearer ${t}`;

    const response = await fetchWithTimeout(`${DESKTOP_API}/api/ontology/export-async/download/${jobId}`, { headers }, 5 * 60_000);
    if (!response.ok) throw new Error(`Export download failed (${response.status}).`);

    const blob = await response.blob();
    triggerBlobDownload(blob, safeName);
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

    try {
        const res = await fetch(`${DESKTOP_API}/api/import-queue/status/${encodeURIComponent(projectId)}`, { headers });
        if (res.ok) {
            const data = await res.json();
            const status = data.status || 'NOT_IN_QUEUE';
            if (status === 'COMPLETED' || status === 'NOT_IN_QUEUE') return 'done';
            if (status === 'FAILED') return 'failed';

            return { state: 'progress', progress: data.progress, message: data.statusMessage };
        }
    } catch (_) {}

    try {
        const res2 = await fetch(`${DESKTOP_API}/api/ontology/cache-status/${encodeURIComponent(projectId)}`, { headers });
        if (res2.ok) {
            const data2 = await res2.json();
            if (data2.owlapiReady) return 'done';
        }
    } catch (_) {}

    try {
        const resStatus = await fetch(`${DESKTOP_API}/api/ontology/status/${encodeURIComponent(projectId)}`, { headers });
        if (resStatus.ok) {
            const st = await resStatus.json();
            const status = st?.data?.status || st?.status;
            if (status === 'ERROR' || status === 'FAILED') return 'failed';
            if (status === 'COMPLETED') return 'done';
        }
    } catch (_) {}

    try {
        const res3 = await fetch(
            `${DESKTOP_API}/api/ontology/classes/top-level/${encodeURIComponent(projectId)}?limit=5`,
            { headers }
        );
        if (res3.ok) {
            const data3 = await res3.json();

            const classes = data3.classes || data3;
            if (Array.isArray(classes) && classes.length > 0) return 'done';
        }
    } catch (_) {}

    return 'unknown';
}

function startImportPoller(projectId) {
    if (_importPollers.has(projectId)) return;

    checkImportStatus(projectId).then(result => {
        if (result === 'done') { dispatchImportCompleted(projectId); return; }
        if (result === 'failed') {
            window.dispatchEvent(new CustomEvent('importStatusUpdate', {
                detail: { type: 'IMPORT_FAILED', status: 'FAILED', projectId }
            }));
            return;
        }

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

                window.dispatchEvent(new CustomEvent('importStatusUpdate', {
                    detail: {
                        type: 'IMPORT_PROGRESS', status: 'PROCESSING', projectId,
                        progress: result.progress || 0,
                        metadata: { message: result.message || 'Importing…' }
                    }
                }));
            } else if (ticks > 400) {

                window.dispatchEvent(new CustomEvent('importStatusUpdate', {
                    detail: {
                        type: 'IMPORT_FAILED',
                        status: 'FAILED',
                        projectId,
                        statusMessage: 'Import timed out after 10 minutes. The file may be too large or the editor is busy.',
                    },
                }));
                clearInterval(poll);
                _importPollers.delete(projectId);
            }
        } catch (_) {}
    }, 1500);

    _importPollers.set(projectId, poll);
}

(function startGlobalImportWatcher() {
    setInterval(async () => {
        try {

            if (document.hidden) return;
            const headers = { 'Content-Type': 'application/json' };
            const t = await getAuthToken();
            if (!t) return;
            headers['Authorization'] = `Bearer ${t}`;
            const res = await fetch(`${DESKTOP_API}/api/import-queue/stats`, { headers });
            if (!res.ok) return;
            const data = await res.json();

            const active = data.activeProjectIds || [];
            active.forEach(pid => { if (pid) startImportPoller(pid); });

            const queued = data.queue || [];
            queued.forEach(item => { if (item.projectId) startImportPoller(item.projectId); });
        } catch (_) {}
    }, 5000);
})();

window.__ONTOCODE_BROWSER_BRIDGE__ = true;

let _zoteroPaging = null;
let _zoteroLibrarySessionSeq = 0;

async function _fetchZoteroPage(start, pageSize, searchQuery) {
    const apiKey = localStorage.getItem('zoteroApiKey');
    const userId = localStorage.getItem('zoteroUserId');

    if (!apiKey || !userId) throw new Error('ZOTERO_NOT_CONFIGURED');

    const libraryType = localStorage.getItem('zoteroLibraryType') || 'user';
    const groupId = localStorage.getItem('zoteroGroupId');
    const libraryPath = libraryType === 'group' && groupId
        ? `groups/${encodeURIComponent(groupId)}`
        : `users/${encodeURIComponent(userId)}`;

    const u = new URL(`https://api.zotero.org/${libraryPath}/items`);
    u.searchParams.set('limit', String(pageSize));
    u.searchParams.set('start', String(start));
    u.searchParams.set('format', 'json');
    u.searchParams.set('include', 'data');
    u.searchParams.set('itemType', '-attachment');
    if (searchQuery) u.searchParams.set('q', searchQuery);

    const resp = await fetch(u.toString(), {
        headers: { 'Zotero-API-Key': apiKey, 'Zotero-API-Version': '3' },
    });
    if (!resp.ok) {
        if (resp.status === 403) throw new Error('Invalid Zotero API key');
        if (resp.status === 404) throw new Error('Zotero user/group not found');
        throw new Error(`Zotero API error: ${resp.status}`);
    }
    const totalResults = parseInt(resp.headers.get('Total-Results') || '0', 10);

    const items = await resp.json();
    return { items, totalResults };
}

contextBridge.exposeInMainWorld('vscode', {
    postMessage: async (message) => {
        switch (message.type) {

            case 'webviewReady':

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

                break;

            case 'openLocalFile':
            case 'importLocalFile':

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
            case 'downloadCurrentOntology': {

                (async () => {
                    const requestId = message.requestId;
                    try {
                        await exportOntologyViaJob(message.projectId, message.format, message.filename);
                        window.dispatchEvent(new MessageEvent('message', {
                            data: { type: 'downloadOntologyComplete', requestId },
                        }));
                    } catch (err) {
                        console.error('[Preload] downloadOntology failed:', err);
                        const msg = (err && err.message) || 'Could not download ontology file';
                        window.dispatchEvent(new MessageEvent('message', {
                            data: { type: 'downloadOntologyFailed', requestId, error: msg, cancelled: msg.includes('cancelled') },
                        }));
                    }
                })();
                break;
            }

            case 'uploadOntology':
            case 'uploadFileToProject':
            case 'uploadOntologyContent':

                if (message.projectId) startImportPoller(message.projectId);
                console.log('[Preload] Upload message – handled by React browser-mode:', message.type);
                break;

            case 'cursorMoved':
            case 'broadcastCursor':

                break;

            case 'requestZoteroLibrary': {
                const searchQuery = (message.searchQuery || '').trim() || undefined;
                const sid = ++_zoteroLibrarySessionSeq;
                const PAGE_SIZE = 100;
                _zoteroPaging = { start: 0, totalResults: Infinity, pageSize: PAGE_SIZE, loading: true, done: false, sessionId: sid, searchQuery };
                (async () => {
                    try {
                        const { items, totalResults } = await _fetchZoteroPage(0, PAGE_SIZE, searchQuery);
                        _zoteroPaging.totalResults = totalResults;
                        _zoteroPaging.start = items.length;
                        _zoteroPaging.loading = false;
                        _zoteroPaging.done = items.length === 0 || _zoteroPaging.start >= totalResults;
                        const knownTotal = Number.isFinite(totalResults) && totalResults >= 0 ? Math.floor(totalResults) : undefined;
                        window.dispatchEvent(new MessageEvent('message', { data: {
                            type: 'zoteroLibraryData',
                            items,
                            hasMore: !_zoteroPaging.done,
                            librarySessionId: sid,
                            ...(knownTotal !== undefined ? { totalResults: knownTotal, loadedSoFar: _zoteroPaging.start } : {}),
                        }}));
                        if (_zoteroPaging.done) {
                            window.dispatchEvent(new MessageEvent('message', { data: { type: 'zoteroLibraryDataComplete', librarySessionId: sid } }));
                        }
                    } catch (err) {
                        console.error('[Preload] requestZoteroLibrary failed:', err);
                        window.dispatchEvent(new MessageEvent('message', { data: {
                            type: 'zoteroLibraryError',
                            error: err.message || 'Failed to load Zotero library',
                            librarySessionId: sid,
                        }}));
                    }
                })();
                break;
            }

            case 'requestZoteroLibraryMore': {
                if (!_zoteroPaging) break;
                if (_zoteroPaging.done || _zoteroPaging.loading) break;
                _zoteroPaging.loading = true;
                const { start: moreStart, pageSize: morePageSize, searchQuery: moreSQ, sessionId: moreSid } = _zoteroPaging;
                (async () => {
                    try {
                        const { items, totalResults } = await _fetchZoteroPage(moreStart, morePageSize, moreSQ);
                        if (Number.isFinite(totalResults) && totalResults > 0) _zoteroPaging.totalResults = totalResults;
                        const got = items.length;
                        _zoteroPaging.start = moreStart + got;
                        _zoteroPaging.loading = false;
                        const done = got === 0 || _zoteroPaging.start >= _zoteroPaging.totalResults || got < morePageSize;
                        _zoteroPaging.done = done;
                        if (got > 0) {
                            const knownTotal = Number.isFinite(_zoteroPaging.totalResults) && _zoteroPaging.totalResults < Number.MAX_SAFE_INTEGER ? Math.floor(_zoteroPaging.totalResults) : undefined;
                            window.dispatchEvent(new MessageEvent('message', { data: {
                                type: 'zoteroLibraryDataAppend',
                                items,
                                hasMore: !done,
                                librarySessionId: moreSid,
                                ...(knownTotal !== undefined ? { totalResults: knownTotal, loadedSoFar: _zoteroPaging.start } : {}),
                            }}));
                        }
                        if (done) {
                            window.dispatchEvent(new MessageEvent('message', { data: { type: 'zoteroLibraryDataComplete', librarySessionId: moreSid } }));
                        }
                    } catch (err) {
                        if (_zoteroPaging) _zoteroPaging.loading = false;
                        console.error('[Preload] requestZoteroLibraryMore failed:', err);
                        window.dispatchEvent(new MessageEvent('message', { data: {
                            type: 'zoteroLibraryError',
                            error: err.message || 'Failed to load more Zotero items',
                            librarySessionId: moreSid,
                        }}));
                    }
                })();
                break;
            }

            case 'insertCitation':
            case 'insertManualCitation':
            case 'insertCitationToGraphDB':
            case 'removeCitationFromGraphDB':

                console.log('[Preload] Citation message – handled by React browser-mode:', message.type);
                break;

            case 'showSubscriptionPlans':
                window.dispatchEvent(new MessageEvent('message', { data: { type: 'showSubscriptionPlans' } }));
                break;

            case 'toggleDevTools':
                ipcRenderer.send('devtools:toggle');
                break;

            case 'duplicateFilePromptResponse':
                break;

            case 'getQueueStatus':

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

                break;

            default:
                console.log('[Preload] Unhandled postMessage type:', message.type);
        }
    }
});

contextBridge.exposeInMainWorld('electronAPI', {

    openFile:  ()                    => ipcRenderer.invoke('file:open'),
    saveAs:    (content, name)       => ipcRenderer.invoke('file:saveAs', { content, defaultName: name }),

    getProfile: ()                   => ipcRenderer.invoke('profile:get'),
    saveProfile: (profile)           => ipcRenderer.invoke('profile:save', profile),

    getConfig:  ()                   => ipcRenderer.invoke('config:get'),

    getServiceStatus: ()             => ipcRenderer.invoke('services:status'),
    ensureFuseki: ()                 => ipcRenderer.invoke('services:ensureFuseki'),
    ensureSwrl:   ()                 => ipcRenderer.invoke('services:ensureSwrl'),

    openLogs: ()                     => ipcRenderer.invoke('logs:open'),

    getAppVersion: ()                => ipcRenderer.invoke('config:get').then((c) => c?.appVersion || '0.0.0'),
    updateGetStatus: ()              => ipcRenderer.invoke('update:getStatus'),
    updateCheck: ()                  => ipcRenderer.invoke('update:check'),
    updateDownload: ()               => ipcRenderer.invoke('update:download'),
    updateInstall: ()                => ipcRenderer.invoke('update:install'),
    onUpdateStatus: (callback) => {
        const handler = (_evt, data) => callback(data);
        ipcRenderer.on('update:status', handler);
        return () => ipcRenderer.removeListener('update:status', handler);
    },

    onMenuOpenFile: (callback)       => ipcRenderer.on('menu:open-file', (_evt, data) => callback(data)),
    onFocusExistingFile: (callback) => ipcRenderer.on('desktop:focus-file', (_evt, data) => callback(data)),

    getActiveFilePath: ()            => ipcRenderer.invoke('file:getActivePath'),
    setActiveFilePath: (filePath)    => ipcRenderer.invoke('file:setActivePath', filePath),
    clearActiveFilePath: ()          => ipcRenderer.invoke('file:clearActivePath'),

    getLicense:      ()              => ipcRenderer.invoke('license:get'),
    importLicense:   (json)          => ipcRenderer.invoke('license:import', json),
    openPurchase:    (plan)          => ipcRenderer.invoke('license:openPurchase', plan),

    syncStatus:      ()              => ipcRenderer.invoke('sync:status'),
    syncDeviceId:    ()              => ipcRenderer.invoke('sync:deviceId'),
    syncFolders:     ()              => ipcRenderer.invoke('sync:folders'),
    syncShareWorkspace: (label)      => ipcRenderer.invoke('sync:shareWorkspace', { label }),
    syncGenerateLink:   (folderIds)  => ipcRenderer.invoke('sync:generateLink', { folderIds }),
    syncParseLink:      (link)       => ipcRenderer.invoke('sync:parseLink', { link }),
    syncAddPeer:        (opts)       => ipcRenderer.invoke('sync:addPeer', opts),
    syncCompletion:     (opts)       => ipcRenderer.invoke('sync:completion', opts),

    pollImportStatus: (projectId)    => startImportPoller(projectId),
});
