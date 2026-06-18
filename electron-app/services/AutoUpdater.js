/**
 * Auto-update via electron-updater (generic provider).
 * Checks the OntoCode API for latest.yml; user must click to download/install.
 */
const { app, Notification } = require('electron');

let autoUpdater = null;
try {
    autoUpdater = require('electron-updater').autoUpdater;
} catch (err) {
    console.warn('[AutoUpdater] electron-updater not bundled — updates disabled:', err.message);
}

const UPDATE_URL = process.env.ONTOCODE_UPDATE_URL
    || 'https://ontocodeapi.selfresearch.org/api/downloads/updates/win';

const CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000; // 4 hours
const STARTUP_DELAY_MS = 30 * 1000;

let mainWindow = null;
let intervalId = null;
let lastStatus = {
    status: 'idle',
    currentVersion: app.getVersion(),
    availableVersion: null,
    percent: 0,
    error: null,
};

function sendToRenderer(channel, payload) {
    if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send(channel, payload);
    }
}

function broadcastStatus(extra = {}) {
    lastStatus = { ...lastStatus, ...extra };
    sendToRenderer('update:status', lastStatus);
}

function showNativeNotification(title, body) {
    if (Notification.isSupported()) {
        new Notification({ title, body }).show();
    }
}

function configure() {
    if (!autoUpdater) return;
    autoUpdater.autoDownload = false;
    autoUpdater.autoInstallOnAppQuit = false;
    autoUpdater.allowDowngrade = false;
    autoUpdater.logger = console;

    autoUpdater.setFeedURL({
        provider: 'generic',
        url: UPDATE_URL,
    });
}

function wireEvents() {
    if (!autoUpdater) return;
    autoUpdater.on('checking-for-update', () => {
        broadcastStatus({ status: 'checking', error: null });
    });

    autoUpdater.on('update-available', (info) => {
        const version = info?.version || 'unknown';
        broadcastStatus({ status: 'available', availableVersion: version, error: null, percent: 0 });
        showNativeNotification(
            'OntoCode update available',
            `Version ${version} is available. Open Help → Check for Updates or use the banner to download.`,
        );
    });

    autoUpdater.on('update-not-available', (info) => {
        broadcastStatus({
            status: 'up-to-date',
            availableVersion: info?.version || null,
            error: null,
            percent: 0,
        });
    });

    autoUpdater.on('download-progress', (progress) => {
        broadcastStatus({
            status: 'downloading',
            percent: Math.round(progress?.percent || 0),
        });
    });

    autoUpdater.on('update-downloaded', (info) => {
        const version = info?.version || lastStatus.availableVersion;
        broadcastStatus({ status: 'downloaded', availableVersion: version, percent: 100 });
        showNativeNotification(
            'OntoCode update ready',
            `Version ${version} downloaded. Click Restart to update in OntoCode.`,
        );
    });

    autoUpdater.on('error', (err) => {
        const message = err?.message || String(err);
        console.warn('[AutoUpdater]', message);
        broadcastStatus({ status: 'error', error: message });
    });
}

function start(window) {
    if (!autoUpdater) {
        console.log('[AutoUpdater] Skipped — module not available');
        return;
    }
    if (!app.isPackaged) {
        console.log('[AutoUpdater] Skipped in unpackaged/dev mode');
        return;
    }
    if (process.platform !== 'win32') {
        console.log('[AutoUpdater] Windows-only feed — skipped on', process.platform);
        return;
    }

    mainWindow = window;
    configure();
    wireEvents();

    setTimeout(() => {
        checkForUpdates(false).catch(() => {});
    }, STARTUP_DELAY_MS);

    if (intervalId) clearInterval(intervalId);
    intervalId = setInterval(() => {
        checkForUpdates(false).catch(() => {});
    }, CHECK_INTERVAL_MS);
}

async function checkForUpdates(manual = true) {
    if (!autoUpdater) {
        return { ...lastStatus, status: 'unavailable', error: 'electron-updater not installed' };
    }
    if (!app.isPackaged || process.platform !== 'win32') {
        return { ...lastStatus, status: 'dev-skipped' };
    }
    try {
        if (manual) broadcastStatus({ status: 'checking', error: null });
        await autoUpdater.checkForUpdates();
        return lastStatus;
    } catch (err) {
        const message = err?.message || String(err);
        broadcastStatus({ status: 'error', error: message });
        return lastStatus;
    }
}

async function downloadUpdate() {
    if (!autoUpdater || !app.isPackaged || process.platform !== 'win32') {
        return { ...lastStatus, status: 'dev-skipped' };
    }
    try {
        broadcastStatus({ status: 'downloading', error: null, percent: 0 });
        await autoUpdater.downloadUpdate();
        return lastStatus;
    } catch (err) {
        const message = err?.message || String(err);
        broadcastStatus({ status: 'error', error: message });
        return lastStatus;
    }
}

function installUpdate() {
    if (!autoUpdater || !app.isPackaged) return false;
    autoUpdater.quitAndInstall(false, true);
    return true;
}

function getStatus() {
    return {
        ...lastStatus,
        currentVersion: app.getVersion(),
        updateUrl: UPDATE_URL,
    };
}

function stop() {
    if (intervalId) {
        clearInterval(intervalId);
        intervalId = null;
    }
    mainWindow = null;
}

module.exports = {
    start,
    stop,
    checkForUpdates,
    downloadUpdate,
    installUpdate,
    getStatus,
};
