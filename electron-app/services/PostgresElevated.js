'use strict';

const { spawn, execFile } = require('child_process');
const EventEmitter = require('events');
const fs = require('fs');
const net = require('net');
const path = require('path');
const client = require('./PgSimpleClient');

const ADMIN_CHECK = '([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent())'
    + '.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)';

let cachedElevated = null;

function isHighIntegrity(whoamiGroups) {
    return /S-1-16-(12288|16384)\b/.test(String(whoamiGroups));
}

function checkWithWhoami() {
    return new Promise((resolve) => {
        execFile('whoami.exe', ['/groups'], { windowsHide: true, timeout: 10000 },
            (err, stdout) => resolve(err ? null : isHighIntegrity(stdout)));
    });
}

function checkWithPowerShell() {
    return new Promise((resolve) => {
        execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', ADMIN_CHECK],
            { windowsHide: true, timeout: 20000 }, (err, stdout) => resolve(!err && /true/i.test(String(stdout))));
    });
}

async function detectElevated() {
    const viaWhoami = await checkWithWhoami();
    return viaWhoami === null ? checkWithPowerShell() : viaWhoami;
}

function isElevatedAdmin() {
    if (cachedElevated) return cachedElevated;
    if (process.env.ONTOCODE_FORCE_ADMIN_PATH === '1') {
        cachedElevated = Promise.resolve(true);
    } else if (process.platform !== 'win32') {
        cachedElevated = Promise.resolve(false);
    } else {
        cachedElevated = detectElevated();
    }
    return cachedElevated;
}

function isInsideStorePackage(dir, platform = process.platform) {
    return platform === 'win32' && /[\\/]WindowsApps[\\/]/i.test(String(dir));
}

function needsOwnCopy(elevated, root, platform = process.platform) {
    return Boolean(elevated) || isInsideStorePackage(root, platform);
}

function resetForTests() {
    cachedElevated = null;
}

function ctlPath(root) {
    return path.join(root, 'bin', process.platform === 'win32' ? 'pg_ctl.exe' : 'pg_ctl');
}

function runPgCtl(root, args, { timeoutMs = 120000 } = {}) {
    return new Promise((resolve, reject) => {
        const child = spawn(ctlPath(root), args, { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
        let output = '';
        const timer = setTimeout(() => {
            try { child.kill('SIGKILL'); } catch (_) {}
            reject(new Error(`pg_ctl timed out after ${Math.round(timeoutMs / 1000)}s`));
        }, timeoutMs);
        child.stdout.on('data', (d) => { output += d; });
        child.stderr.on('data', (d) => { output += d; });
        child.on('error', (err) => { clearTimeout(timer); reject(err); });
        child.on('exit', (code) => { clearTimeout(timer); resolve({ code, output }); });
    });
}

function processAlive(pid) {
    try {
        process.kill(pid, 0);
        return true;
    } catch (err) {
        return err.code === 'EPERM';
    }
}

class ElevatedServer extends EventEmitter {
    constructor(pid, { pollMs = 1500, alive = processAlive, unref = true } = {}) {
        super();
        this.pid = pid;
        this.exitCode = null;
        this.killed = false;
        this.timer = setInterval(() => {
            if (!alive(pid)) this.markExited(this._ontoStopping ? 0 : 1);
        }, pollMs);
        if (unref && this.timer.unref) this.timer.unref();
    }

    markExited(code) {
        if (this.exitCode !== null) return;
        this.exitCode = code;
        clearInterval(this.timer);
        this.emit('exit', code);
    }

    kill() {
        this.killed = true;
        try { process.kill(this.pid); } catch (_) {}
    }
}

function readPostmasterPid(dataDir) {
    const text = fs.readFileSync(path.join(dataDir, 'postmaster.pid'), 'utf8');
    const pid = parseInt(text.split('\n')[0], 10);
    if (!Number.isInteger(pid)) throw new Error('PostgreSQL started but its process id could not be read');
    return pid;
}

async function startServer({ root, dataDir, serverOptions, logFile, timeoutSeconds = 60 }) {
    fs.mkdirSync(path.dirname(logFile), { recursive: true });
    const result = await runPgCtl(root, [
        'start', '-D', dataDir, '-l', logFile, '-w', '-t', String(timeoutSeconds), '-o', serverOptions.join(' '),
    ], { timeoutMs: (timeoutSeconds + 30) * 1000 });
    if (result.code !== 0) {
        const tail = result.output.split('\n').map((l) => l.trim()).filter(Boolean).slice(-4).join(' | ');
        throw new Error(`PostgreSQL did not start through pg_ctl (exit ${result.code}): ${tail.substring(0, 400)}`);
    }
    return new ElevatedServer(readPostmasterPid(dataDir));
}

async function stopServer(root, dataDir) {
    await runPgCtl(root, ['stop', '-D', dataDir, '-m', 'fast', '-w', '-t', '30'], { timeoutMs: 60000 });
}

const STAGE_MARKER = '.staged-from';

function postgresExeName() {
    return process.platform === 'win32' ? 'postgres.exe' : 'postgres';
}

function fingerprint(root) {
    const stat = fs.statSync(path.join(root, 'bin', postgresExeName()));
    return `${root}|${stat.size}|${Math.round(stat.mtimeMs)}`;
}

async function stageRuntime(root, stagingDir) {
    const wanted = fingerprint(root);
    const marker = path.join(stagingDir, STAGE_MARKER);
    const ready = fs.existsSync(marker) && fs.readFileSync(marker, 'utf8') === wanted
        && fs.existsSync(path.join(stagingDir, 'bin', postgresExeName()));
    if (ready) return stagingDir;
    const parent = path.dirname(stagingDir);
    const partial = `${stagingDir}.partial-${process.pid}`;
    fs.mkdirSync(parent, { recursive: true });
    for (const entry of fs.readdirSync(parent)) {
        if (entry.startsWith(`${path.basename(stagingDir)}.partial-`)) fs.rmSync(path.join(parent, entry), { recursive: true, force: true });
    }
    fs.rmSync(stagingDir, { recursive: true, force: true });
    await fs.promises.cp(root, partial, { recursive: true, filter: (src) => path.basename(src) !== 'cluster-template' });
    fs.writeFileSync(path.join(partial, STAGE_MARKER), wanted, 'utf8');
    fs.renameSync(partial, stagingDir);
    return stagingDir;
}

function freePort() {
    return new Promise((resolve, reject) => {
        const server = net.createServer();
        server.once('error', reject);
        server.listen(0, '127.0.0.1', () => {
            const { port } = server.address();
            server.close(() => resolve(port));
        });
    });
}

async function createDatabase({ root, dataDir, password, user, database, serverArgs, logFile }) {
    const port = await freePort();
    const options = serverArgs(dataDir, port, '64MB').slice(2);
    await startServer({ root, dataDir, serverOptions: options, logFile });
    try {
        await client.run({ port, user, password, database: 'postgres', sql: `CREATE DATABASE ${database} OWNER ${user}` });
    } finally {
        await stopServer(root, dataDir);
    }
}

module.exports = {
    isElevatedAdmin,
    isHighIntegrity,
    isInsideStorePackage,
    needsOwnCopy,
    resetForTests,
    ElevatedServer,
    startServer,
    stopServer,
    createDatabase,
    stageRuntime,
    freePort,
};
