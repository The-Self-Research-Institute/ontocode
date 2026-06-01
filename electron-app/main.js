/**
 * Electron main process — OntoCode Desktop
 *
 * Start-up sequence:
 *   1. Show splash window immediately (fast — no services needed).
 *   2. Check Java 17+ is available; abort with dialog if not.
 *   3. Start MongoDB → Fuseki → OWL Editor (sequential, each health-checked).
 *   4. Close splash, open main window pointing at the local OWL Editor.
 *
 * Shutdown:
 *   Trap window-all-closed / before-quit and stop all three services.
 */

const {
    app, BrowserWindow, ipcMain, dialog, Notification, shell, Menu, Tray,
} = require('electron');
const path     = require('path');
const fs       = require('fs');
const Store    = require('electron-store');
const svcMgr   = require('./services/ServiceManager');
const syncMgr  = require('./services/SyncManager');
const proxy    = require('./services/ProxyServer');
const detectJava = require('./scripts/detect-java');

// ── Dev mode ─────────────────────────────────────────────────────────────────
// Set ELECTRON_IS_DEV=1 to skip bundled service startup and point at Docker.
// ELECTRON_DEV_API_URL overrides the backend URL (default: http://localhost:8083).
const IS_DEV = process.env.ELECTRON_IS_DEV === '1' || (!app.isPackaged && process.env.ELECTRON_IS_DEV !== '0');
const DEV_API_URL = process.env.ELECTRON_DEV_API_URL || 'http://localhost:8083';
const VITE_URL    = process.env.ELECTRON_VITE_URL    || 'http://localhost:5173';

// ── Auth token store (encrypted at rest) ────────────────────────────────────
const store = new Store({ encryptionKey: 'ontocode-desktop-v1' });

let splashWindow = null;
let mainWindow   = null;
let tray         = null;
let servicesRunning = false;

// ── Splash window ─────────────────────────────────────────────────────────────
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

// ── Main window ───────────────────────────────────────────────────────────────
function createMainWindow() {
    mainWindow = new BrowserWindow({
        width:  1400,
        height: 900,
        minWidth:  960,
        minHeight: 600,
        title: 'OntoCode',
        show: false,
        // Hide the native menu bar — the React app has its own internal menu.
        // The native menu is still accessible via Alt key on Windows.
        autoHideMenuBar: true,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
            webSecurity: false,
            // Allow unload event handlers — Axios uses these to cancel
            // in-flight XHR requests on page exit. Without this Chromium
            // logs a [Violation] Permissions policy violation: unload.
            allowRunningInsecureContent: false,
        },
    });

    // Remove the native application menu entirely so it doesn't appear on
    // first render before autoHideMenuBar takes effect.
    mainWindow.setMenu(null);

    // In production, the routing proxy merges auth + OWL editor under one URL.
    const editorUrl = IS_DEV ? DEV_API_URL : `http://127.0.0.1:${proxy.PROXY_PORT}`;

    if (IS_DEV) {
        // Hot-reload mode: load Vite dev server (run `npm run dev` in webview-src first)
        mainWindow.loadURL(VITE_URL);
        mainWindow.webContents.openDevTools();
    } else {
        const distIndex = app.isPackaged
            ? path.join(__dirname, 'renderer', 'dist', 'index.html')
            : path.join(__dirname, '../ontology-vscode-extension/webview-src/dist/index.html');
        mainWindow.loadFile(distIndex);
        mainWindow.webContents.openDevTools();
    }

    mainWindow.webContents.on('did-finish-load', () => {
        mainWindow.webContents.executeJavaScript(`
            window.__DESKTOP_API_URL__ = '${editorUrl}';
            window.__DESKTOP_MODE__ = true;
            window.__IS_DEV__ = ${IS_DEV};
        `);
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

    // Fallback: if ready-to-show doesn't fire within 8 seconds, show anyway
    mainWindow.webContents.once('did-finish-load', () => setTimeout(showMain, 500));
    setTimeout(showMain, 8000);

    mainWindow.on('closed', () => { mainWindow = null; });
}

// ── App lifecycle ─────────────────────────────────────────────────────────────
app.whenReady().then(async () => {
    createSplash();

    // Log forwarded to splash screen
    svcMgr.onLog((level, msg) => {
        if (splashWindow && !splashWindow.isDestroyed()) {
            splashWindow.webContents.send('service-log', { level, msg });
        }
    });

    // ── Start Syncthing (optional, both modes) ─────────────────────────────
    syncMgr.onLog((level, msg) => sendSplashLog(level, `[Sync] ${msg}`));
    syncMgr.start().catch(e => console.warn('Sync engine failed to start:', e.message));

    if (IS_DEV) {
        // ── DEV MODE: skip bundled services, connect to Docker stack ───────
        sendSplashLog('info', `Dev mode — connecting to ${DEV_API_URL}…`);
        sendSplashLog('ok',   'Skipping bundled service startup (using Docker)');
        servicesRunning = false;   // desktop services not owned by this process
        createMainWindow();
        setupTray();
    } else {
        // ── PRODUCTION MODE ────────────────────────────────────────────────
        sendSplashLog('info', 'Checking Java runtime…');
        const javaOk = await detectJava.check();
        if (!javaOk) {
            if (splashWindow) splashWindow.close();
            await dialog.showMessageBox({
                type: 'error',
                title: 'Java not found',
                message: 'OntoCode Desktop requires Java 17 or newer.\n\nPlease install the Java Development Kit (JDK 17+) and restart OntoCode.',
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
        setupTray();
    }

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) createMainWindow();
    });
});

// Prevent quit while services are shutting down
let isQuitting = false;

app.on('before-quit', (event) => {
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

// ── IPC handlers ──────────────────────────────────────────────────────────────

/** Auth token storage (used by the React app via preload bridge) */
ipcMain.handle('auth:get',   ()         => store.get('authToken', null));
ipcMain.handle('auth:save',  (_, token) => store.set('authToken', token));
ipcMain.handle('auth:clear', ()         => store.delete('authToken'));

/** Local display-name storage (desktop-only, no account needed) */
ipcMain.handle('profile:get',  ()      => store.get('localProfile', null));
ipcMain.handle('profile:save', (_, p)  => store.set('localProfile', p));

/** Expose backend URL to the renderer — also handles sync config:get-sync */
function getConfig() {
    return {
        apiBaseUrl: IS_DEV ? DEV_API_URL : `http://127.0.0.1:${proxy.PROXY_PORT}`,
        fusekiUrl:  IS_DEV ? `http://localhost:${svcMgr.FUSEKI_PORT}` : `http://127.0.0.1:${svcMgr.FUSEKI_PORT}`,
        isDesktop:  true,
        isDev:      IS_DEV,
    };
}
ipcMain.handle('config:get', () => getConfig());
ipcMain.on('config:get-sync', (event) => { event.returnValue = getConfig(); });

// ── License system ────────────────────────────────────────────────────────────

const LICENSE_PATH = path.join(app.getPath('userData'), 'license.json');

function readLicense() {
    try {
        if (fs.existsSync(LICENSE_PATH)) {
            return JSON.parse(fs.readFileSync(LICENSE_PATH, 'utf8'));
        }
    } catch (_) {}
    // Default FREE license
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

/** Open a native file dialog */
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

    const filePath  = result.filePaths[0];
    const fileName  = path.basename(filePath);
    const fileContent = fs.readFileSync(filePath, 'utf8');
    const fileSize  = Buffer.byteLength(fileContent, 'utf8');
    return { fileName, fileContent, fileSize, filePath };
});

/** Save a file via native Save As dialog */
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

/** Native OS notification */
ipcMain.handle('notification:show', (_, { title, message }) => {
    if (Notification.isSupported()) {
        new Notification({ title, body: message }).show();
    }
});

/** Service status (used by status bar in renderer) */
ipcMain.handle('services:status', () => svcMgr.status());

/** Open logs directory in file manager */
ipcMain.handle('logs:open', () => {
    shell.openPath(path.join(app.getPath('userData'), 'logs'));
});

// ── Sync / Share IPC ──────────────────────────────────────────────────────────

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

/** Open a workspace folder picker, then share it via Syncthing. */
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

// ── System tray ───────────────────────────────────────────────────────────────
function setupTray() {
    const iconFile = path.join(__dirname, 'assets', 'tray-icon.png');
    if (!fs.existsSync(iconFile)) return;   // skip if icon not bundled yet

    tray = new Tray(iconFile);
    tray.setToolTip('OntoCode');
    tray.setContextMenu(Menu.buildFromTemplate([
        { label: 'Open OntoCode', click: () => { if (mainWindow) mainWindow.show(); else createMainWindow(); } },
        { type: 'separator' },
        { label: 'Open Logs…', click: () => shell.openPath(path.join(app.getPath('userData'), 'logs')) },
        { type: 'separator' },
        { label: 'Quit', click: () => app.quit() },
    ]));
    tray.on('double-click', () => { if (mainWindow) mainWindow.show(); });
}

// ── App menu ──────────────────────────────────────────────────────────────────
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
                { label: 'Documentation', click: () => shell.openExternal('https://ontocode.selfresearch.org/docs') },
                { label: 'Report Issue…', click: () => shell.openExternal('https://github.com/kkpranesh/ontocode/issues') },
            ],
        },
    ];
    Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

// ── Splash helpers ─────────────────────────────────────────────────────────────
function sendSplashLog(level, msg) {
    if (splashWindow && !splashWindow.isDestroyed()) {
        splashWindow.webContents.send('service-log', { level, msg });
    }
}
