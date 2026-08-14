

const {
    app, BrowserWindow, ipcMain, dialog, Notification, shell, Menu, Tray,
} = require('electron');
const path     = require('path');
const fs       = require('fs');
const Store    = require('electron-store');
const svcMgr   = require('./services/ServiceManager');
const syncMgr  = require('./services/SyncManager');
const proxy    = require('./services/ProxyServer');
const autoUpdater = require('./services/AutoUpdater');
const detectJava = require('./scripts/detect-java');

const IS_DEV = process.env.ELECTRON_IS_DEV === '1' || (!app.isPackaged && process.env.ELECTRON_IS_DEV !== '0');
const DEV_API_URL = process.env.ELECTRON_DEV_API_URL || 'http://localhost:8083';
const VITE_URL    = process.env.ELECTRON_VITE_URL    || 'http://localhost:5173';

const ONTOLOGY_EXTENSIONS = new Set(['owl', 'rdf', 'ttl', 'n3', 'nt', 'jsonld', 'ofn']);
let pendingOpenFile = null;
let pendingFocusFile = null;
let lastDeliveredFilePath = '';
let lastDeliveredAt = 0;
let activeOntologyFilePath = '';

function isOntologyFilePath(filePath) {
    if (!filePath || typeof filePath !== 'string') return false;
    const ext = path.extname(filePath).slice(1).toLowerCase();
    return ONTOLOGY_EXTENSIONS.has(ext);
}

function extractOntologyPathFromArgv(argv) {
    for (let i = argv.length - 1; i >= 0; i--) {
        const arg = argv[i];
        if (!arg || arg.startsWith('-')) continue;
        try {
            const resolved = path.resolve(arg);
            if (isOntologyFilePath(resolved) && fs.existsSync(resolved)) {
                return resolved;
            }
        } catch (_) { /* ignore bad paths */ }
    }
    return null;
}

function normalizeFilePath(filePath) {
    try {
        return path.resolve(filePath).toLowerCase();
    } catch (_) {
        return '';
    }
}

function sendFocusExistingFile(filePath) {
    const resolved = path.resolve(filePath);
    const win = mainWindow && !mainWindow.isDestroyed() ? mainWindow : null;
    const payload = {
        focusOnly: true,
        filePath: resolved,
        fileName: path.basename(resolved),
    };
    if (!win?.webContents || win.webContents.isLoading()) {
        pendingFocusFile = payload;
        return;
    }
    focusExistingWindow();
    win.webContents.send('desktop:focus-file', payload);
}

function flushPendingFocusFile() {
    if (!pendingFocusFile) return;
    const payload = pendingFocusFile;
    pendingFocusFile = null;
    const win = mainWindow && !mainWindow.isDestroyed() ? mainWindow : null;
    if (!win?.webContents) return;
    focusExistingWindow();
    win.webContents.send('desktop:focus-file', payload);
}

function isActiveOntologyFile(filePath) {
    const normalized = normalizeFilePath(filePath);
    return !!normalized && normalized === activeOntologyFilePath;
}

function openOntologyFileFromPath(filePath) {
    const resolved = path.resolve(filePath);
    if (!isOntologyFilePath(resolved) || !fs.existsSync(resolved)) return;

    if (isActiveOntologyFile(resolved)) {
        sendFocusExistingFile(resolved);
        return;
    }

    try {
        deliverOpenFileToRenderer(readOntologyFilePayload(resolved));
    } catch (err) {
        dialog.showErrorBox('Open ontology file', `Could not open:\n${resolved}\n\n${err.message}`);
    }
}

function readOntologyFilePayload(filePath) {
    const resolved = path.resolve(filePath);
    const fileContent = fs.readFileSync(resolved, 'utf8');
    return {
        fileName: path.basename(resolved),
        fileContent,
        fileSize: Buffer.byteLength(fileContent, 'utf8'),
        filePath: resolved,
    };
}

function focusExistingWindow() {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    if (mainWindow.isMinimized()) mainWindow.restore();
    if (!mainWindow.isVisible()) mainWindow.show();
    mainWindow.focus();
}

function deliverOpenFileToRenderer(payload) {
    const normalized = payload.filePath ? normalizeFilePath(payload.filePath) : '';
    if (normalized && normalized === activeOntologyFilePath) {
        sendFocusExistingFile(payload.filePath);
        return;
    }

    const now = Date.now();
    if (payload.filePath && payload.filePath === lastDeliveredFilePath && now - lastDeliveredAt < 2000) {
        focusExistingWindow();
        return;
    }
    lastDeliveredFilePath = payload.filePath || '';
    lastDeliveredAt = now;

    const win = mainWindow && !mainWindow.isDestroyed() ? mainWindow : null;
    if (!win?.webContents || win.webContents.isLoading()) {
        pendingOpenFile = payload;
        return;
    }
    if (normalized) {
        activeOntologyFilePath = normalized;
    }
    focusExistingWindow();
    win.webContents.send('menu:open-file', payload);
}

function flushPendingOpenFile() {
    if (!pendingOpenFile) return;
    const payload = pendingOpenFile;
    pendingOpenFile = null;
    deliverOpenFileToRenderer(payload);
}

const gotSingleInstanceLock = app.requestSingleInstanceLock();
if (!gotSingleInstanceLock) {
    app.quit();
} else {
    app.on('second-instance', (_event, argv) => {
        focusExistingWindow();
        const filePath = extractOntologyPathFromArgv(argv);
        if (!filePath) return;
        openOntologyFileFromPath(filePath);
    });
}

app.on('open-file', (event, filePath) => {
    event.preventDefault();
    openOntologyFileFromPath(filePath);
});

const store = new Store({ encryptionKey: 'ontocode-desktop-v1' });

let splashWindow = null;
let mainWindow   = null;
let tray         = null;
let servicesRunning = false;

function createSplash() {
    splashWindow = new BrowserWindow({
        width:  560,
        height: 380,
        frame:  false,
        resizable: false,
        center: true,
        alwaysOnTop: true,
        webPreferences: {
            preload: path.join(__dirname, 'splash-preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
        },
    });
    splashWindow.loadFile(path.join(__dirname, 'splash.html'));
    splashWindow.on('closed', () => { splashWindow = null; });
}

function createMainWindow() {
    mainWindow = new BrowserWindow({
        width:  1400,
        height: 900,
        minWidth:  960,
        minHeight: 600,
        title: 'OntoCode Studio',
        show: false,

        autoHideMenuBar: true,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
            webSecurity: false,

            allowRunningInsecureContent: false,
        },
    });

    mainWindow.setMenu(null);

    mainWindow.webContents.on('before-input-event', (event, input) => {
        const isF12 = input.type === 'keyDown' && input.key === 'F12';
        const isCtrlShiftI = input.type === 'keyDown' && input.key === 'I' && input.control && input.shift;
        if (isF12 || isCtrlShiftI) {
            mainWindow.webContents.toggleDevTools();
            event.preventDefault();
        }
    });

    const editorUrl = IS_DEV ? DEV_API_URL : `http://127.0.0.1:${proxy.PROXY_PORT}`;

    if (IS_DEV) {

        mainWindow.loadURL(VITE_URL);
        mainWindow.webContents.openDevTools();
    } else {
        const distIndex = app.isPackaged
            ? path.join(__dirname, 'renderer', 'dist', 'index.html')
            : path.join(__dirname, '../ontology-vscode-extension/webview-src/dist/index.html');
        mainWindow.loadFile(distIndex);
    }

    mainWindow.webContents.on('did-finish-load', () => {
        mainWindow.webContents.executeJavaScript(`
            window.__DESKTOP_API_URL__ = '${editorUrl}';
            window.__DESKTOP_MODE__ = true;
            window.__IS_DEV__ = ${IS_DEV};
            // Plugins (UMD bundles) call fetch() against window.API_BASE_URL.
            // apiClient initialised it to the self-hosted default (localhost:80)
            // before this injection ran — overwrite so plugin requests hit the
            // desktop proxy instead of a dead port.
            window.API_BASE_URL = '${editorUrl}';
        `);
        flushPendingOpenFile();
        flushPendingFocusFile();
        const initialFile = extractOntologyPathFromArgv(process.argv);
        if (initialFile) {
            openOntologyFileFromPath(initialFile);
        }
    });

    mainWindow.webContents.setWindowOpenHandler(({ url }) => {
        shell.openExternal(url);
        return { action: 'deny' };
    });

    function showMain() {
        if (mainWindow && !mainWindow.isDestroyed() && !mainWindow.isVisible()) {
            if (splashWindow && !splashWindow.isDestroyed()) splashWindow.close();
            mainWindow.show();
        }
    }

    mainWindow.once('ready-to-show', showMain);

    mainWindow.webContents.once('did-finish-load', () => setTimeout(showMain, 500));
    setTimeout(showMain, 8000);

    let closeConfirmed = false;
    mainWindow.on('close', (e) => {
        if (closeConfirmed) return;
        e.preventDefault();
        (async () => {
            try {
                const pid = await mainWindow.webContents.executeJavaScript('window.__ONTOCODE_PROJECT_ID__ || null');
                if (pid) {
                    const st = await fetch(`${editorUrl}/api/desktop/draft-status/${encodeURIComponent(pid)}`)
                        .then(r => (r.ok ? r.json() : null)).catch(() => null);
                    if (st && st.hasDraft) {
                        const choice = dialog.showMessageBoxSync(mainWindow, {
                            type: 'question',
                            buttons: ['Save & Exit', 'Exit Without Saving', 'Cancel'],
                            defaultId: 0,
                            cancelId: 2,
                            message: 'You have unsaved changes',
                            detail: 'Exit Without Saving keeps your changes as a draft — they will be recovered next time you open this project.',
                        });
                        if (choice === 2) return; // Cancel — stay open
                        if (choice === 0) {
                            await fetch(`${editorUrl}/api/desktop/save/${encodeURIComponent(pid)}`, { method: 'POST' })
                                .catch(() => { /* draft stays on disk — recoverable */ });
                        }
                    }
                }
            } catch (_) { /* backend unreachable — draft stays on disk, safe to exit */ }
            closeConfirmed = true;
            mainWindow.close();
        })();
    });

    mainWindow.on('closed', () => {
        autoUpdater.stop();
        mainWindow = null;
    });
}

app.whenReady().then(async () => {
    createSplash();

    svcMgr.onLog((level, msg) => {
        if (splashWindow && !splashWindow.isDestroyed()) {
            splashWindow.webContents.send('service-log', { level, msg });
        }
    });

    syncMgr.onLog((level, msg) => sendSplashLog(level, `[Sync] ${msg}`));
    syncMgr.start().catch(e => console.warn('Sync engine failed to start:', e.message));

    if (IS_DEV) {

        sendSplashLog('info', `Dev mode — connecting to ${DEV_API_URL}…`);
        sendSplashLog('ok',   'Skipping bundled service startup (using Docker)');
        servicesRunning = false;   // desktop services not owned by this process
        createMainWindow();
        setupTray();
    } else {

        sendSplashLog('info', 'Checking Java runtime…');
        const javaOk = await detectJava.check();
        if (!javaOk) {
            if (splashWindow) splashWindow.close();
            await dialog.showMessageBox({
                type: 'error',
                title: 'Java not found',
                message: 'OntoCode Studio requires Java 17 or newer.\n\nPlease install the Java Development Kit (JDK 17+) and restart OntoCode Studio.',
                buttons: ['Open Download Page', 'Quit'],
            }).then(({ response }) => {
                if (response === 0) shell.openExternal('https://adoptium.net/');
            });
            app.quit();
            return;
        }
        sendSplashLog('ok', `Java ${javaOk} detected`);

        try {
            await svcMgr.startAll();
            await proxy.start(svcMgr.DESKTOP_PORT, svcMgr.SWRL_PORT);
            sendSplashLog('ok', `Routing proxy ready on port ${proxy.PROXY_PORT}`);
        } catch (err) {
            if (splashWindow) splashWindow.close();
            await dialog.showMessageBox({
                type: 'error',
                title: 'Startup failed',
                message: `Failed to start backend services:\n\n${err.message}\n\nCheck the logs in:\n${app.getPath('userData')}/logs`,
                buttons: ['OK'],
            });
            app.quit();
            return;
        }

        servicesRunning = true;
        createMainWindow();
        if (mainWindow) autoUpdater.start(mainWindow);
        setupTray();
    }

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createMainWindow();
    });
});

let isQuitting = false;
let isInstallingUpdate = false;

async function installAppUpdate() {
    if (isInstallingUpdate) return { ok: false, error: 'Update already in progress' };
    isInstallingUpdate = true;
    isQuitting = true;
    try {
        await Promise.all([svcMgr.stopAll(), proxy.stop(), syncMgr.stop()]);
    } catch (err) {
        console.warn('[Update] Shutdown before install:', err?.message || err);
    }
    servicesRunning = false;
    const result = await autoUpdater.installUpdate();
    if (!result?.ok) {
        isInstallingUpdate = false;
        isQuitting = false;
    }
    return result;
}

app.on('before-quit', (event) => {
    if (isInstallingUpdate) return;
    if ((servicesRunning && !IS_DEV) && !isQuitting) {
        event.preventDefault();
        isQuitting = true;
        Promise.all([svcMgr.stopAll(), proxy.stop(), syncMgr.stop()]).finally(() => {
            servicesRunning = false;
            app.quit();
        });
    } else if (!isQuitting && syncMgr.isRunning()) {
        event.preventDefault();
        isQuitting = true;
        syncMgr.stop().finally(() => app.quit());
    }
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
});

ipcMain.handle('file:getActivePath', () => activeOntologyFilePath);
ipcMain.handle('file:setActivePath', (_event, filePath) => {
    activeOntologyFilePath = filePath ? normalizeFilePath(filePath) : '';
});
ipcMain.handle('file:clearActivePath', () => {
    activeOntologyFilePath = '';
});

ipcMain.handle('auth:get',   ()         => store.get('authToken', null));
ipcMain.handle('auth:save',  (_, token) => store.set('authToken', token));
ipcMain.handle('auth:clear', ()         => store.delete('authToken'));

ipcMain.handle('profile:get',  ()      => store.get('localProfile', null));
ipcMain.handle('profile:save', (_, p)  => store.set('localProfile', p));

function getConfig() {
    return {
        apiBaseUrl: IS_DEV ? DEV_API_URL : `http://127.0.0.1:${proxy.PROXY_PORT}`,
        fusekiUrl:  IS_DEV ? `http://localhost:${svcMgr.FUSEKI_PORT}` : `http://127.0.0.1:${svcMgr.FUSEKI_PORT}`,
        isDesktop:  true,
        isDev:      IS_DEV,
        appVersion: app.getVersion(),
    };
}
ipcMain.handle('config:get', () => getConfig());
ipcMain.on('config:get-sync', (event) => { event.returnValue = getConfig(); });

const LICENSE_PATH = path.join(app.getPath('userData'), 'license.json');

function readLicense() {
    try {
        if (fs.existsSync(LICENSE_PATH)) {
            return JSON.parse(fs.readFileSync(LICENSE_PATH, 'utf8'));
        }
    } catch (_) {}

    const free = {
        version: 1, plan: 'FREE', email: '', name: 'Desktop User',
        issuedAt: new Date().toISOString(), expiresAt: null,
        features: { maxProjects: 10, sync: true, reasoner: true, collaboration: false },
    };
    fs.writeFileSync(LICENSE_PATH, JSON.stringify(free, null, 2), 'utf8');
    return free;
}

ipcMain.handle('license:get',    ()         => readLicense());
ipcMain.handle('license:import', (_, json)  => {
    try {
        const parsed = JSON.parse(json);
        fs.writeFileSync(LICENSE_PATH, JSON.stringify(parsed, null, 2), 'utf8');
        return { ok: true, license: parsed };
    } catch (e) { return { ok: false, error: e.message }; }
});
ipcMain.handle('license:openPurchase', (_, plan) => {
    const deviceId = store.get('deviceId') || require('crypto').randomUUID();
    store.set('deviceId', deviceId);
    shell.openExternal(`https://ontocode.selfresearch.org/desktop-pricing?plan=${plan || 'pro'}&device=${deviceId}`);
});

ipcMain.handle('file:open', async () => {
    const win = mainWindow || BrowserWindow.getFocusedWindow();
    const result = await dialog.showOpenDialog(win, {
        title: 'Open Ontology File',
        filters: [
            { name: 'Ontology Files', extensions: ['owl', 'rdf', 'ttl', 'n3', 'nt', 'jsonld', 'ofn'] },
            { name: 'All Files', extensions: ['*'] },
        ],
        properties: ['openFile'],
    });
    if (result.canceled || !result.filePaths.length) return null;

    const filePath = result.filePaths[0];
    if (isActiveOntologyFile(filePath)) {
        sendFocusExistingFile(filePath);
        return { focusOnly: true, filePath: path.resolve(filePath), fileName: path.basename(filePath) };
    }

    const fileName  = path.basename(filePath);
    const fileContent = fs.readFileSync(filePath, 'utf8');
    const fileSize  = Buffer.byteLength(fileContent, 'utf8');
    const resolved = path.resolve(filePath);
    activeOntologyFilePath = normalizeFilePath(resolved);
    return { fileName, fileContent, fileSize, filePath: resolved };
});

ipcMain.handle('file:saveAs', async (_, { content, defaultName }) => {
    const win = mainWindow || BrowserWindow.getFocusedWindow();
    const result = await dialog.showSaveDialog(win, {
        title: 'Save Ontology File',
        defaultPath: defaultName || 'ontology.owl',
        filters: [
            { name: 'OWL/XML', extensions: ['owl'] },
            { name: 'Turtle', extensions: ['ttl'] },
            { name: 'RDF/XML', extensions: ['rdf'] },
            { name: 'N-Triples', extensions: ['nt'] },
        ],
    });
    if (result.canceled || !result.filePath) return null;
    fs.writeFileSync(result.filePath, content, 'utf8');
    return result.filePath;
});

ipcMain.handle('notification:show', (_, { title, message }) => {
    if (Notification.isSupported()) {
        new Notification({ title, body: message }).show();
    }
});

ipcMain.handle('services:status', () => svcMgr.status());
ipcMain.handle('services:ensureFuseki', () => svcMgr.ensureFuseki());
ipcMain.handle('services:ensureSwrl', () => svcMgr.ensureSwrl());

ipcMain.on('devtools:toggle', () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.toggleDevTools();
    }
});

ipcMain.handle('logs:open', () => {
    shell.openPath(path.join(app.getPath('userData'), 'logs'));
});

ipcMain.handle('update:getStatus', () => autoUpdater.getStatus());
ipcMain.handle('update:check', () => autoUpdater.checkForUpdates(true));
ipcMain.handle('update:download', () => autoUpdater.downloadUpdate());
ipcMain.handle('update:install', () => installAppUpdate());

ipcMain.handle('sync:status',        ()                          => ({ running: syncMgr.isRunning() }));
ipcMain.handle('sync:deviceId',      ()                          => syncMgr.getDeviceId());
ipcMain.handle('sync:folders',       ()                          => syncMgr.listFolders());
ipcMain.handle('sync:shareFolder',   (_, { folderPath, label }) => syncMgr.shareFolder(folderPath, label));
ipcMain.handle('sync:addPeer',       (_, { deviceId, folderId, peerName }) => syncMgr.addPeer(deviceId, folderId, peerName));
ipcMain.handle('sync:removePeer',    (_, { deviceId, folderId }) => syncMgr.removePeer(deviceId, folderId));
ipcMain.handle('sync:generateLink',  (_, { folderIds })          => syncMgr.generateShareLink(folderIds));
ipcMain.handle('sync:parseLink',     (_, { link })               => syncMgr.parseShareLink(link));
ipcMain.handle('sync:completion',    (_, { folderId, deviceId }) => syncMgr.folderCompletion(folderId, deviceId));
ipcMain.handle('sync:setPaused',     (_, { folderId, paused })   => syncMgr.setFolderPaused(folderId, paused));

ipcMain.handle('sync:shareWorkspace', async (_, { label }) => {
    const win = mainWindow || BrowserWindow.getFocusedWindow();
    const result = await dialog.showOpenDialog(win, {
        title: 'Select workspace folder to share',
        properties: ['openDirectory'],
    });
    if (result.canceled || !result.filePaths.length) return null;
    const folderPath = result.filePaths[0];
    const folderId   = await syncMgr.shareFolder(folderPath, label || path.basename(folderPath));
    const link       = await syncMgr.generateShareLink([folderId]);
    return { folderPath, folderId, link };
});

function setupTray() {
    const iconFile = path.join(__dirname, 'assets', 'tray-icon.png');
    if (!fs.existsSync(iconFile)) return;   // skip if icon not bundled yet

    tray = new Tray(iconFile);
    tray.setToolTip('OntoCode Studio');
    tray.setContextMenu(Menu.buildFromTemplate([
        { label: 'Open OntoCode Studio', click: () => { focusExistingWindow(); if (!mainWindow) createMainWindow(); } },
        { type: 'separator' },
        { label: 'Open Logs…', click: () => shell.openPath(path.join(app.getPath('userData'), 'logs')) },
        { type: 'separator' },
        { label: 'Quit', click: () => app.quit() },
    ]));
    tray.on('double-click', () => { focusExistingWindow(); });
}

function setupMenu(win) {
    const isMac = process.platform === 'darwin';
    const template = [
        ...(isMac ? [{ role: 'appMenu' }] : []),
        {
            label: 'File',
            submenu: [
                {
                    label: 'Open Ontology File…',
                    accelerator: 'CmdOrCtrl+O',
                    click: async () => {
                        const result = await dialog.showOpenDialog(win, {
                            title: 'Open Ontology File',
                            filters: [
                                { name: 'Ontology Files', extensions: ['owl', 'rdf', 'ttl', 'n3', 'nt', 'jsonld'] },
                            ],
                            properties: ['openFile'],
                        });
                        if (!result.canceled && result.filePaths.length) {
                            const filePath = result.filePaths[0];
                            const fileName = path.basename(filePath);
                            const fileContent = fs.readFileSync(filePath, 'utf8');
                            win.webContents.send('menu:open-file', {
                                fileName, fileContent, filePath,
                                fileSize: Buffer.byteLength(fileContent, 'utf8'),
                            });
                        }
                    },
                },
                { type: 'separator' },
                isMac ? { role: 'close' } : { role: 'quit' },
            ],
        },
        { role: 'editMenu' },
        { role: 'viewMenu' },
        {
            label: 'Tools',
            submenu: [
                {
                    label: 'Open Logs…',
                    click: () => shell.openPath(path.join(app.getPath('userData'), 'logs')),
                },
                {
                    label: 'Service Status',
                    click: () => {
                        const s = svcMgr.status();
                        dialog.showMessageBox(win, {
                            title: 'Service Status',
                            message: [
                                `MongoDB:  ${s.mongo   ? '✓ Running' : '✗ Stopped'}`,
                                `Fuseki:   ${s.fuseki  ? '✓ Running' : '✗ Stopped'}`,
                                `Desktop:  ${s.desktop ? '✓ Running' : '✗ Stopped'}  (auth + editor + plugin)`,
                                `SWRL:     ${s.swrl    ? '✓ Running' : '✗ Stopped'}  (optional)`,
                            ].join('\n'),
                            buttons: ['OK'],
                        });
                    },
                },
            ],
        },
        { role: 'windowMenu' },
        {
            role: 'help',
            submenu: [
                {
                    label: `Version ${app.getVersion()}`,
                    enabled: false,
                },
                { type: 'separator' },
                {
                    label: 'About OntoCode Studio',
                    click: () => {
                        dialog.showMessageBox(win, {
                            title: 'About OntoCode Studio',
                            message: 'OntoCode Studio',
                            detail: `Version ${app.getVersion()}\n\nOWL ontology editor — offline capable.\n\nUninstall via Windows Settings → Apps → OntoCode Studio.`,
                            buttons: ['OK'],
                        });
                    },
                },
                {
                    label: 'Check for Updates…',
                    click: async () => {
                        const result = await autoUpdater.checkForUpdates(true);
                        if (result.status === 'dev-skipped') {
                            dialog.showMessageBox(win, {
                                title: 'Updates',
                                message: 'Updates are only checked in the packaged desktop app.',
                                buttons: ['OK'],
                            });
                            return;
                        }
                        if (result.status === 'up-to-date') {
                            dialog.showMessageBox(win, {
                                title: 'No updates',
                                message: `OntoCode Studio ${app.getVersion()} is up to date.`,
                                buttons: ['OK'],
                            });
                        }
                    },
                },
                { type: 'separator' },
                { label: 'Documentation', click: () => shell.openExternal('https://ontocode.selfresearch.org/docs') },
                { label: 'Report Issue…', click: () => shell.openExternal('https://github.com/kkpranesh/ontocode/issues') },
            ],
        },
    ];
    Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

function sendSplashLog(level, msg) {
    if (splashWindow && !splashWindow.isDestroyed()) {
        splashWindow.webContents.send('service-log', { level, msg });
    }
}
