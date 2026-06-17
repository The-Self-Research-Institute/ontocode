/**
 * Auto-update via electron-updater (generic provider).
 * Checks the OntoCode API for latest.yml and notifies the renderer like Cursor.
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
    autoUpdater.autoDownload = true;
    autoUpdater.autoInstallOnAppQuit = true;
    autoUpdater.allowDowngrade = false;
    autoUpdater.logger = console;

    // Generic provider — latest.yml served by OntoCode API
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
        broadcastStatus({ status: 'available', availableVersion: version, error: null });
        showNativeNotification(
            'OntoCode update available',
            `Version ${version} is ready to download.`,
        );
    });

    autoUpdater.on('update-not-available', () => {
        broadcastStatus({ status: 'up-to-date', availableVersion: null, error: null });
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
            `Version ${version} will install when you restart, or click Restart to update now.`,
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
    installUpdate,
    getStatus,
};
