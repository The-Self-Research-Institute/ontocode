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

function isElevatedAdmin() {
    if (cachedElevated) return cachedElevated;
    if (process.env.ONTOCODE_FORCE_ADMIN_PATH === '1') {
        cachedElevated = Promise.resolve(true);
    } else if (process.platform !== 'win32') {
        cachedElevated = Promise.resolve(false);
    } else {
        cachedElevated = new Promise((resolve) => {
            execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', ADMIN_CHECK],
                { windowsHide: true, timeout: 20000 }, (err, stdout) => resolve(!err && /true/i.test(String(stdout))));
        });
    }
    return cachedElevated;
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
    resetForTests,
    ElevatedServer,
    startServer,
    stopServer,
    createDatabase,
    freePort,
};
