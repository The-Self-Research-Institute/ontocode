
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { app, Notification, shell } = require('electron');

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
const MANUAL_DOWNLOAD_URL = 'https://ontocodeapi.selfresearch.org/api/downloads/windows-x64';

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

function updaterCacheDir() {
    return path.join(process.env.LOCALAPPDATA || app.getPath('userData'), 'ontocode-desktop-updater');
}

function resolveCachedInstallerPath() {
    const cacheDir = updaterCacheDir();
    const pendingDir = path.join(cacheDir, 'pending');
    const infoPath = path.join(pendingDir, 'update-info.json');

    if (fs.existsSync(infoPath)) {
        try {
            const info = JSON.parse(fs.readFileSync(infoPath, 'utf8'));
            if (info?.fileName) {
                const candidate = path.join(pendingDir, info.fileName);
                if (fs.existsSync(candidate)) return candidate;
            }
        } catch (err) {
            console.warn('[AutoUpdater] Could not read update-info.json:', err.message);
        }
    }

    const fallbacks = [
        path.join(pendingDir, 'windows-x64'),
        path.join(cacheDir, 'installer.exe'),
    ];
    for (const candidate of fallbacks) {
        if (fs.existsSync(candidate)) return candidate;
    }
    return null;
}

function ensureInstallerExePath(sourcePath) {
    if (sourcePath.toLowerCase().endsWith('.exe')) return sourcePath;
    const dest = path.join(path.dirname(sourcePath), 'OntoCode-Update-Installer.exe');
    try {
        fs.copyFileSync(sourcePath, dest);
        return dest;
    } catch (err) {
        console.warn('[AutoUpdater] Could not copy installer to .exe:', err.message);
        return sourcePath;
    }
}

function spawnDetached(cmd, args) {
    return new Promise((resolve, reject) => {
        try {
            const child = spawn(cmd, args, {
                detached: true,
                stdio: 'ignore',
                windowsHide: false,
            });
            child.on('error', reject);
            child.unref();
            resolve(true);
        } catch (err) {
            reject(err);
        }
    });
}

async function launchCachedInstaller(installerPath) {
    const exePath = ensureInstallerExePath(installerPath);
    const args = ['--updated', '--force-run'];
    const elevate = path.join(process.resourcesPath, 'elevate.exe');

    console.log('[AutoUpdater] Launching installer:', exePath);

    try {
        await spawnDetached(exePath, args);
        return true;
    } catch (err) {
        console.warn('[AutoUpdater] Direct spawn failed:', err.message);
    }

    if (fs.existsSync(elevate)) {
        try {
            await spawnDetached(elevate, [exePath, ...args]);
            return true;
        } catch (err) {
            console.warn('[AutoUpdater] elevate.exe spawn failed:', err.message);
        }
    }

    const openErr = await shell.openPath(exePath);
    if (openErr) {
        throw new Error(openErr);
    }
    return true;
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

async function installUpdate() {
    if (!app.isPackaged) return { ok: false, error: 'Updates only work in the installed desktop app' };

    const cached = resolveCachedInstallerPath();
    if (cached) {
        try {
            await launchCachedInstaller(cached);
            setImmediate(() => app.quit());
            return { ok: true };
        } catch (err) {
            const message = err?.message || String(err);
            console.error('[AutoUpdater] Cached installer launch failed:', message);
            broadcastStatus({ status: 'error', error: message });
            return { ok: false, error: message, manualDownloadUrl: MANUAL_DOWNLOAD_URL };
        }
    }

    if (autoUpdater) {
        autoUpdater.quitAndInstall(false, true);
        return { ok: true };
    }

    return { ok: false, error: 'No update downloaded yet', manualDownloadUrl: MANUAL_DOWNLOAD_URL };
}

function getStatus() {
    const cached = resolveCachedInstallerPath();
    return {
        ...lastStatus,
        currentVersion: app.getVersion(),
        updateUrl: UPDATE_URL,
        manualDownloadUrl: MANUAL_DOWNLOAD_URL,
        cachedInstaller: cached || undefined,
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
    resolveCachedInstallerPath,
    MANUAL_DOWNLOAD_URL,
};
