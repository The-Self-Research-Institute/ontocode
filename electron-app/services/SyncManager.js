/**
 * SyncManager — OntoCode Desktop
 *
 * Manages decentralized peer-to-peer sync via an embedded Syncthing process.
 * Syncthing handles file-level sync between devices with no central server.
 *
 * Architecture:
 *   - Syncthing binary lives in resources/backend/syncthing/<platform>/
 *   - Each workspace folder is shared as a Syncthing "folder"
 *   - Users pair devices by exchanging Device IDs (or scanning a QR code)
 *   - Syncthing's REST API (port 18384) is used to add/remove shared folders
 *   - The React UI reads sync state via IPC → SyncManager → Syncthing REST API
 *
 * Share links:
 *   - A "share link" encodes { deviceId, apiKey, folderIds } as a base64 URL
 *   - The receiving device imports the link, adds the peer, and accepts the folder
 *   - This is decentralized — the two devices sync directly or via relays
 */

const { app } = require('electron');
const { spawn } = require('child_process');
const path  = require('path');
const fs    = require('fs');
const http  = require('http');
const https = require('https');
const crypto = require('crypto');

// ── Constants ────────────────────────────────────────────────────────────────

const SYNC_PORT     = 18384;   // Syncthing GUI/REST API port (offset from default 8384)
const SYNC_TCP_PORT = 22000;   // Syncthing sync protocol port
const SYNC_API_KEY  = generateOrLoadApiKey();

const RESOURCES_DIR = app.isPackaged
    ? path.join(process.resourcesPath, 'backend')
    : path.join(__dirname, '..', 'resources', 'backend');

const SYNC_DATA_DIR = path.join(app.getPath('userData'), 'sync');
const SYNC_CONFIG_DIR = path.join(SYNC_DATA_DIR, 'config');
const SYNC_CONFIG_FILE = path.join(SYNC_CONFIG_DIR, 'config.xml');

// ── State ────────────────────────────────────────────────────────────────────
let syncProcess = null;
let _logCallback = null;

// ── Key storage ──────────────────────────────────────────────────────────────
function generateOrLoadApiKey() {
    const keyFile = path.join(
        app.isPackaged ? app.getPath('userData') : path.join(__dirname, '..'),
        '.sync-api-key',
    );
    if (fs.existsSync(keyFile)) {
        return fs.readFileSync(keyFile, 'utf8').trim();
    }
    const key = crypto.randomBytes(16).toString('hex');
    try { fs.writeFileSync(keyFile, key, 'utf8'); } catch (_) {}
    return key;
}

// ── Public API ───────────────────────────────────────────────────────────────

module.exports = {
    SYNC_PORT,

    onLog(cb) { _logCallback = cb; },

    /** Start Syncthing. Resolves when its REST API is ready. */
    async start() {
        const bin = syncBin();
        if (!bin) {
            log('warn', 'Syncthing binary not found — sync unavailable');
            return false;
        }

        ensureDirs();
        ensureSyncConfig();

        log('info', 'Starting Syncthing sync engine…');
        const args = [
            'serve',
            '--no-browser',
            '--no-restart',
            `--gui-address=127.0.0.1:${SYNC_PORT}`,
            `--gui-apikey=${SYNC_API_KEY}`,
            `--home=${SYNC_CONFIG_DIR}`,
        ];

        syncProcess = spawn(bin, args, {
            env: { ...process.env },
            stdio: ['ignore', 'pipe', 'pipe'],
            windowsHide: true,
        });

        const logFile = path.join(app.getPath('userData'), 'logs', 'syncthing.log');
        const logStream = fs.createWriteStream(logFile, { flags: 'a' });

        syncProcess.stdout.on('data', d => { logStream.write(d); });
        syncProcess.stderr.on('data', d => { logStream.write(d); });
        syncProcess.on('exit', code => {
            logStream.end();
            if (code !== 0 && code !== null) log('error', `Syncthing exited with code ${code}`);
        });

        try {
            await waitForSyncApi(30000);
            log('ok', `Syncthing ready on port ${SYNC_PORT}`);
            return true;
        } catch (e) {
            log('warn', `Syncthing did not start: ${e.message}`);
            return false;
        }
    },

    /** Stop Syncthing gracefully. */
    async stop() {
        if (!syncProcess || syncProcess.killed) return;
        return new Promise(resolve => {
            syncProcess.once('exit', resolve);
            try { syncProcess.kill('SIGTERM'); } catch (_) {}
            setTimeout(() => {
                try { syncProcess.kill('SIGKILL'); } catch (_) {}
                resolve();
            }, 4000);
        });
    },

    /** Is the sync engine running? */
    isRunning() {
        return !!syncProcess && !syncProcess.killed && syncProcess.exitCode === null;
    },

    /**
     * Get the local device ID (Syncthing's permanent identity).
     * Returns a string like "ABCDE12-..." or null if unavailable.
     */
    async getDeviceId() {
        try {
            const status = await apiGet('/rest/system/status');
            return status.myID || null;
        } catch { return null; }
    },

    /**
     * List all folders currently shared via Syncthing.
     * Returns [{ id, label, path, paused, status }]
     */
    async listFolders() {
        try {
            const config = await apiGet('/rest/config');
            return (config.folders || []).map(f => ({
                id:     f.id,
                label:  f.label || f.id,
                path:   f.path,
                paused: f.paused || false,
            }));
        } catch { return []; }
    },

    /**
     * Share a workspace folder. Adds it to Syncthing config.
     * @param {string} folderPath  Absolute path to the workspace folder
     * @param {string} label       Display name (e.g. "MyOntology")
     * @returns {string} folderId  The Syncthing folder ID to share with peers
     */
    async shareFolder(folderPath, label) {
        const folderId = `ontocode-${crypto.randomBytes(4).toString('hex')}`;
        const config   = await apiGet('/rest/config');
        config.folders = config.folders || [];

        if (config.folders.find(f => f.path === folderPath)) {
            const existing = config.folders.find(f => f.path === folderPath);
            return existing.id;
        }

        config.folders.push({
            id:              folderId,
            label:           label,
            path:            folderPath,
            type:            'sendreceive',
            rescanIntervalS: 60,
            fsWatcherEnabled: true,
            fsWatcherDelayS:  10,
            ignorePerms:     false,
            autoNormalize:   true,
            devices:         [],
        });

        await apiPut('/rest/config', config);
        log('ok', `Folder "${label}" added to sync (id: ${folderId})`);
        return folderId;
    },

    /**
     * Add a remote peer and share a folder with them.
     * @param {string} deviceId   Remote device ID (from their share link)
     * @param {string} folderId   Folder to share with them
     * @param {string} peerName   Display name for the peer
     */
    async addPeer(deviceId, folderId, peerName) {
        const config = await apiGet('/rest/config');

        // Add device if not already there
        config.devices = config.devices || [];
        if (!config.devices.find(d => d.deviceID === deviceId)) {
            config.devices.push({
                deviceID:    deviceId,
                name:        peerName || 'OntoCode Peer',
                compression: 'metadata',
                introducer:  false,
                skipIntroductionRemovals: false,
                autoAcceptFolders: false,
            });
        }

        // Add device to the folder's share list
        const folder = (config.folders || []).find(f => f.id === folderId);
        if (folder) {
            folder.devices = folder.devices || [];
            if (!folder.devices.find(d => d.deviceID === deviceId)) {
                folder.devices.push({ deviceID: deviceId, introducedBy: '', encryptionPassword: '' });
            }
        }

        await apiPut('/rest/config', config);
        log('ok', `Peer ${deviceId.slice(0, 7)}… added to folder ${folderId}`);
    },

    /**
     * Remove a peer from a shared folder.
     */
    async removePeer(deviceId, folderId) {
        const config = await apiGet('/rest/config');
        const folder = (config.folders || []).find(f => f.id === folderId);
        if (folder) {
            folder.devices = (folder.devices || []).filter(d => d.deviceID !== deviceId);
        }
        await apiPut('/rest/config', config);
    },

    /**
     * Generate a shareable link that encodes this device's ID + folder IDs.
     * The receiving device imports this link to start syncing.
     * Format: ontocode-sync://<base64(JSON)>
     */
    async generateShareLink(folderIds) {
        const deviceId = await this.getDeviceId();
        if (!deviceId) throw new Error('Sync engine not running');
        const payload = JSON.stringify({ deviceId, folderIds, v: 1 });
        const encoded = Buffer.from(payload).toString('base64url');
        return `ontocode-sync://${encoded}`;
    },

    /**
     * Parse a share link received from another user.
     * Returns { deviceId, folderIds }
     */
    parseShareLink(link) {
        const encoded = link.replace('ontocode-sync://', '');
        try {
            return JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8'));
        } catch {
            throw new Error('Invalid share link');
        }
    },

    /** Get real-time sync completion % for a folder (0-100). */
    async folderCompletion(folderId, deviceId) {
        try {
            const data = await apiGet(`/rest/db/completion?folder=${folderId}&device=${deviceId}`);
            return Math.round(data.completion || 0);
        } catch { return null; }
    },

    /** Pause or resume a shared folder. */
    async setFolderPaused(folderId, paused) {
        const config = await apiGet('/rest/config');
        const folder = (config.folders || []).find(f => f.id === folderId);
        if (folder) {
            folder.paused = paused;
            await apiPut('/rest/config', config);
        }
    },
};

// ── Syncthing binary locator ─────────────────────────────────────────────────

function syncBin() {
    const platform = process.platform;
    const ext = platform === 'win32' ? '.exe' : '';
    const candidates = [
        path.join(RESOURCES_DIR, 'syncthing', platform, `syncthing${ext}`),
        path.join(RESOURCES_DIR, 'syncthing', `syncthing${ext}`),
    ];
    for (const c of candidates) {
        if (fs.existsSync(c)) return c;
    }
    // Fall back to PATH
    try {
        require('child_process').execSync(platform === 'win32' ? 'where syncthing' : 'which syncthing');
        return 'syncthing';
    } catch { return null; }
}

// ── Config helpers ───────────────────────────────────────────────────────────

function ensureDirs() {
    [SYNC_CONFIG_DIR].forEach(d => { if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true }); });
}

function ensureSyncConfig() {
    if (fs.existsSync(SYNC_CONFIG_FILE)) return;
    // Syncthing generates its own config on first run; nothing to pre-create.
}

// ── Syncthing REST helpers ───────────────────────────────────────────────────

function syncApiRequest(method, urlPath, body) {
    return new Promise((resolve, reject) => {
        const opts = {
            hostname: '127.0.0.1',
            port:     SYNC_PORT,
            path:     urlPath,
            method,
            headers: {
                'X-API-Key': SYNC_API_KEY,
                'Content-Type': 'application/json',
            },
        };
        const req = http.request(opts, (res) => {
            let data = '';
            res.on('data', c => { data += c; });
            res.on('end', () => {
                try { resolve(JSON.parse(data)); }
                catch { resolve(data); }
            });
        });
        req.on('error', reject);
        if (body) req.write(JSON.stringify(body));
        req.end();
    });
}

function apiGet(urlPath)        { return syncApiRequest('GET',  urlPath, null); }
function apiPut(urlPath, body)  { return syncApiRequest('PUT',  urlPath, body); }

// ── Startup wait ─────────────────────────────────────────────────────────────

function waitForSyncApi(timeoutMs) {
    return new Promise((resolve, reject) => {
        const deadline = Date.now() + timeoutMs;
        function attempt() {
            if (Date.now() > deadline) return reject(new Error('Timeout waiting for Syncthing API'));
            const req = http.get({
                hostname: '127.0.0.1',
                port: SYNC_PORT,
                path: '/rest/system/ping',
                headers: { 'X-API-Key': SYNC_API_KEY },
            }, (res) => {
                if (res.statusCode === 200) resolve();
                else setTimeout(attempt, 1000);
                res.resume();
            });
            req.on('error', () => setTimeout(attempt, 1000));
            req.setTimeout(1500, () => { req.destroy(); setTimeout(attempt, 1000); });
        }
        attempt();
    });
}

// ── Logger ───────────────────────────────────────────────────────────────────

function log(level, msg) {
    console.log(`[SYNC][${level.toUpperCase()}] ${msg}`);
    if (_logCallback) _logCallback(level, msg);
}
