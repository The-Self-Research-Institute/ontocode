'use strict';

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const pg = require('./PostgresRuntime');
const pgCtl = require('./PostgresElevated');
const policy = require('./ResourcePolicy');

const SPARE_BYTES = 1024 * 1024 * 1024;
const SERVER_START_SECONDS = 300;
const GB = 1024 * 1024 * 1024;
const VERIFIED_MARKER = '.upgrade-verified';

function legacyRootFor(resourcesDir, platform = process.platform) {
    return path.join(resourcesDir, 'postgres-legacy', platform);
}

function workDirFor(dataDir) {
    return `${dataDir}-upgrade`;
}

function decide({ dataDir, bundledRoot, legacyRoot }) {
    const have = pg.dataMajor(dataDir);
    const want = pg.bundledMajor(bundledRoot);
    if (!have || !want || have === want) return { action: 'start' };
    const legacy = legacyRoot && fs.existsSync(pg.binPath(legacyRoot, 'postgres')) ? pg.bundledMajor(legacyRoot) : null;
    if (legacy === have && Number(have) < Number(want)) return { action: 'upgrade', from: have, to: want };
    return { action: 'refuse', from: have, to: want };
}

function folderBytes(dir) {
    let total = 0;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) total += folderBytes(full);
        else if (entry.isFile()) total += fs.statSync(full).size;
    }
    return total;
}

function stamp(date) {
    const p = (n) => String(n).padStart(2, '0');
    return `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}-${p(date.getHours())}${p(date.getMinutes())}${p(date.getSeconds())}`;
}

function restoreEmptyDirs(dataDir) {
    for (const dir of pg.EMPTY_CLUSTER_DIRS) fs.mkdirSync(path.join(dataDir, dir), { recursive: true });
}

function runConverter({ javaBin, desktopJar, env, log, logFile }) {
    return new Promise((resolve, reject) => {
        const out = fs.createWriteStream(logFile, { flags: 'a' });
        const child = spawn(javaBin, ['-Xmx512m', '-jar', desktopJar, '--upgrade-database'], {
            env: { ...process.env, ...env },
            windowsHide: true,
        });
        let pending = '';
        const onData = (chunk) => {
            out.write(chunk);
            pending += chunk.toString('utf8');
            const lines = pending.split(/\r?\n/);
            pending = lines.pop();
            for (const line of lines) {
                if (line.startsWith('[upgrade]')) log('info', line.substring('[upgrade]'.length).trim());
            }
        };
        child.stdout.on('data', onData);
        child.stderr.on('data', (chunk) => out.write(chunk));
        child.on('error', (err) => { out.end(); reject(err); });
        child.on('exit', (code) => { out.end(); resolve(code === null ? -1 : code); });
    });
}

function describe(outcome, logFile) {
    if (outcome.reason === 'disk') {
        const needGb = Math.ceil(outcome.neededBytes / GB);
        return `OntoCode Studio needs to upgrade its local database once, which needs about ${needGb} GB of free disk space. `
            + 'Until then it keeps using the previous database engine, and your data is unchanged. '
            + 'Free some space and restart the app to finish the upgrade.';
    }
    return 'OntoCode Studio could not upgrade its local database this time, so it keeps using the previous database engine. '
        + `Your data is unchanged, and the upgrade will be tried again at the next start. Details: ${logFile}`;
}

function safeAppend(file, text) {
    try {
        fs.appendFileSync(file, text);
    } catch (_) {
    }
}

async function run(options) {
    const logFile = path.join(options.logsDir, 'database-upgrade.log');
    try {
        return await attempt(options, logFile);
    } catch (err) {
        (options.log || (() => {}))('warn', `Database upgrade stopped: ${err.message}`);
        safeAppend(logFile, `[upgrade] FAILED: ${err.message}\n`);
        if (fs.existsSync(options.dataDir)) {
            try {
                fs.rmSync(workDirFor(options.dataDir), { recursive: true, force: true });
            } catch (_) {
            }
        }
        return { ok: false, reason: 'error', code: null, message: err.message, logFile };
    }
}

async function attempt(options, logFile) {
    const {
        dataDir, bundledRoot, legacyRoot, password, javaBin, desktopJar, sharedBuffers, logsDir, from, to,
        log = () => {}, elevated = false, deps = {},
    } = options;
    const d = {
        freeBytes: policy.freeBytes,
        folderBytes,
        freePort: pgCtl.freePort,
        startServer: pgCtl.startServer,
        stopServer: pgCtl.stopServer,
        buildCluster: pg.buildCluster,
        applyPassword: pg.applyPassword,
        runConverter,
        now: () => new Date(),
        ...deps,
    };
    const workDir = workDirFor(dataDir);
    const oldServerLog = path.join(logsDir, `database-upgrade-postgres${from}.log`);
    const newServerLog = path.join(logsDir, `database-upgrade-postgres${to}.log`);
    fs.mkdirSync(logsDir, { recursive: true });

    if (fs.existsSync(workDir)) {
        log('warn', 'An earlier database upgrade did not finish; starting it again');
        await d.stopServer(bundledRoot, workDir).catch(() => {});
        fs.rmSync(workDir, { recursive: true, force: true });
    }
    await d.stopServer(legacyRoot, dataDir).catch(() => {});
    const neededBytes = 2 * d.folderBytes(dataDir) + SPARE_BYTES;
    const free = d.freeBytes(path.dirname(dataDir));
    if (free !== null && free !== undefined && free < neededBytes) {
        return { ok: false, reason: 'disk', neededBytes, freeBytes: free, logFile };
    }

    log('info', `Upgrading your local database from PostgreSQL ${from} to ${to} (one time)…`);
    let oldRunning = false;
    let newRunning = false;
    let code;
    try {
        pg.clearStalePid(dataDir);
        restoreEmptyDirs(dataDir);
        await d.applyPassword(legacyRoot, dataDir, password, { elevated });
        const oldPort = await d.freePort();
        await d.startServer({
            root: legacyRoot, dataDir, logFile: oldServerLog, timeoutSeconds: SERVER_START_SECONDS,
            serverOptions: pg.serverArgs(dataDir, oldPort, sharedBuffers).slice(2),
        });
        oldRunning = true;
        await d.buildCluster(bundledRoot, workDir, password, { log, elevated });
        const newPort = await d.freePort();
        await d.startServer({
            root: bundledRoot, dataDir: workDir, logFile: newServerLog, timeoutSeconds: SERVER_START_SECONDS,
            serverOptions: pg.serverArgs(workDir, newPort, sharedBuffers).slice(2),
        });
        newRunning = true;
        code = await d.runConverter({
            javaBin, desktopJar, log, logFile,
            env: {
                ONTOCODE_UPGRADE_SOURCE_URL: pg.jdbcUrl(oldPort),
                ONTOCODE_UPGRADE_TARGET_URL: pg.jdbcUrl(newPort),
                ONTOCODE_DB_USER: pg.DB_USER,
                ONTOCODE_DB_PASSWORD: password,
            },
        });
    } catch (err) {
        code = null;
        log('warn', `Database upgrade stopped: ${err.message}`);
        safeAppend(logFile, `[upgrade] FAILED: ${err.message}\n`);
    } finally {
        if (newRunning) await d.stopServer(bundledRoot, workDir).catch(() => {});
        if (oldRunning) await d.stopServer(legacyRoot, dataDir).catch(() => {});
    }

    if (code !== 0) {
        fs.rmSync(workDir, { recursive: true, force: true });
        return { ok: false, reason: 'error', code, logFile };
    }
    fs.writeFileSync(path.join(workDir, VERIFIED_MARKER), `${from}\n`);
    const backupDir = `${dataDir}-${from}-backup-${stamp(d.now())}`;
    fs.renameSync(dataDir, backupDir);
    try {
        fs.renameSync(workDir, dataDir);
    } catch (err) {
        fs.renameSync(backupDir, dataDir);
        fs.rmSync(workDir, { recursive: true, force: true });
        return { ok: false, reason: 'error', code: null, message: err.message, logFile };
    }
    fs.rmSync(path.join(dataDir, VERIFIED_MARKER), { force: true });
    return { ok: true, backupDir, logFile };
}

async function discardLeftover({ dataDir, bundledRoot, log = () => {}, stopServer = pgCtl.stopServer }) {
    const workDir = workDirFor(dataDir);
    if (!fs.existsSync(dataDir) || !fs.existsSync(workDir)) return false;
    log('info', 'Removing a database copy left behind by an earlier upgrade attempt');
    await stopServer(bundledRoot, workDir).catch(() => {});
    try {
        fs.rmSync(workDir, { recursive: true, force: true });
        return true;
    } catch (_) {
        return false;
    }
}

function recover({ dataDir, log = () => {} }) {
    const workDir = workDirFor(dataDir);
    if (fs.existsSync(dataDir) || !fs.existsSync(path.join(workDir, VERIFIED_MARKER))) return false;
    fs.renameSync(workDir, dataDir);
    fs.rmSync(path.join(dataDir, VERIFIED_MARKER), { force: true });
    log('info', 'Finished an upgraded local database that was interrupted while it was being switched in');
    return true;
}

module.exports = {
    decide, run, recover, discardLeftover, describe, legacyRootFor, workDirFor, folderBytes, runConverter, VERIFIED_MARKER,
};
