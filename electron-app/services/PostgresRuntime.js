'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

const DB_USER = 'ontocode';
const DB_NAME = 'ontocode';
const SAFE_PASSWORD = /^[A-Za-z0-9_-]{16,}$/;

function exeName(name, platform = process.platform) {
    return platform === 'win32' ? `${name}.exe` : name;
}

function binPath(root, name, platform = process.platform) {
    const bundled = path.join(root, 'bin', exeName(name, platform));
    return fs.existsSync(bundled) ? bundled : name;
}

function readTrimmed(file) {
    try { return fs.readFileSync(file, 'utf8').trim(); } catch (_) { return null; }
}

function bundledMajor(root) {
    const value = readTrimmed(path.join(root, 'PG_MAJOR'));
    return value && /^\d+$/.test(value) ? value : null;
}

function dataMajor(dataDir) {
    const value = readTrimmed(path.join(dataDir, 'PG_VERSION'));
    return value && /^\d+$/.test(value) ? value : null;
}

function clusterState(dataDir) {
    if (!fs.existsSync(dataDir)) return 'missing';
    if (dataMajor(dataDir)) return 'present';
    return fs.readdirSync(dataDir).length === 0 ? 'missing' : 'incomplete';
}

function checkVersion(root, dataDir) {
    const have = dataMajor(dataDir);
    const want = bundledMajor(root);
    if (!have || !want || have === want) return { ok: true, dataMajor: have, bundledMajor: want };
    return { ok: false, dataMajor: have, bundledMajor: want };
}

function runWithInput(bin, args, input, { timeoutMs = 120000, env = process.env } = {}) {
    return new Promise((resolve, reject) => {
        const child = spawn(bin, args, { env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
        let output = '';
        let settled = false;
        const timer = setTimeout(() => {
            try { child.kill('SIGKILL'); } catch (_) {}
            finish(new Error(`${path.basename(bin)} timed out after ${Math.round(timeoutMs / 1000)}s`));
        }, timeoutMs);
        function finish(err, result) {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            if (err) reject(err); else resolve(result);
        }
        child.stdout.on('data', (d) => { output += d; });
        child.stderr.on('data', (d) => { output += d; });
        child.on('error', (err) => finish(err));
        child.on('exit', (code) => finish(null, { code, output }));
        child.stdin.on('error', () => {});
        child.stdin.end(input || '');
    });
}

async function runSql(root, dataDir, sql, { timeoutMs = 60000, database = 'postgres' } = {}) {
    const lines = Array.isArray(sql) ? sql : [sql];
    const input = lines.map((l) => l.replace(/\s*\n\s*/g, ' ')).join('\n') + '\n';
    const result = await runWithInput(binPath(root, 'postgres'),
        ['--single', '-D', dataDir, '-c', 'log_min_messages=error', database], input, { timeoutMs });
    return result;
}

function failure(label, result) {
    const tail = result.output.split('\n').map((l) => l.trim()).filter(Boolean).slice(-6).join(' | ');
    return new Error(`${label} failed (exit ${result.code}): ${tail.substring(0, 600)}`);
}

async function runInitdb(root, dataDir, pwFile, extra) {
    return runWithInput(binPath(root, 'initdb'), [
        '-D', dataDir, '-U', DB_USER, '-E', 'UTF8',
        '--auth-local=scram-sha-256', '--auth-host=scram-sha-256',
        `--pwfile=${pwFile}`, '--no-sync', ...extra,
    ], '', { timeoutMs: 300000 });
}

async function buildCluster(root, dataDir, password, { log = () => {} } = {}) {
    if (!SAFE_PASSWORD.test(password)) throw new Error('database password has an unsupported format');
    fs.mkdirSync(path.dirname(dataDir), { recursive: true });
    const pwFile = path.join(os.tmpdir(), `ontocode-pw-${crypto.randomBytes(6).toString('hex')}`);
    fs.writeFileSync(pwFile, password, { mode: 0o600 });
    try {
        let result = await runInitdb(root, dataDir, pwFile, ['--locale=C', '--locale-provider=icu', '--icu-locale=en-US']);
        if (result.code !== 0) {
            log('warn', 'initdb with the ICU collation failed; retrying with the plain C collation');
            fs.rmSync(dataDir, { recursive: true, force: true });
            result = await runInitdb(root, dataDir, pwFile, ['--locale=C']);
        }
        if (result.code !== 0) throw failure('initdb', result);
    } finally {
        fs.rmSync(pwFile, { force: true });
    }
    const created = await runSql(root, dataDir, `CREATE DATABASE ${DB_NAME} OWNER ${DB_USER}`, { timeoutMs: 300000 });
    if (created.code !== 0) throw failure('create database', created);
}

const EMPTY_CLUSTER_DIRS = [
    'pg_commit_ts',
    'pg_dynshmem',
    'pg_logical/mappings',
    'pg_logical/snapshots',
    'pg_notify',
    'pg_replslot',
    'pg_serial',
    'pg_snapshots',
    'pg_stat_tmp',
    'pg_tblspc',
    'pg_twophase',
    'pg_wal/archive_status',
    'pg_wal/summaries',
];

function restoreEmptyClusterDirs(dataDir) {
    for (const dir of EMPTY_CLUSTER_DIRS) fs.mkdirSync(path.join(dataDir, dir), { recursive: true });
}

function secureClusterPermissions(dir, top = true) {
    if (process.platform === 'win32') return;
    fs.chmodSync(dir, 0o700);
    if (!top) return;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            secureClusterPermissions(full);
        } else if (entry.isFile()) {
            fs.chmodSync(full, 0o600);
        }
    }
}

async function prepareCluster(root, dataDir, password, { log = () => {} } = {}) {
    const state = clusterState(dataDir);
    if (state === 'present') {
        if (checkVersion(root, dataDir).ok) {
            restoreEmptyClusterDirs(dataDir);
            secureClusterPermissions(dataDir, false);
        }
        return { created: false };
    }
    if (state === 'incomplete') {
        const aside = `${dataDir}.incomplete-${Date.now()}`;
        fs.renameSync(dataDir, aside);
        log('warn', `The database folder was incomplete; moved it to ${aside}`);
    }
    const template = path.join(root, 'cluster-template');
    if (fs.existsSync(path.join(template, 'PG_VERSION'))) {
        fs.mkdirSync(path.dirname(dataDir), { recursive: true });
        fs.cpSync(template, dataDir, { recursive: true });
        restoreEmptyClusterDirs(dataDir);
        secureClusterPermissions(dataDir);
        return { created: true, fromTemplate: true };
    }
    await buildCluster(root, dataDir, password, { log });
    return { created: true, fromTemplate: false };
}

async function applyPassword(root, dataDir, password) {
    if (!SAFE_PASSWORD.test(password)) throw new Error('database password has an unsupported format');
    let result = await runSql(root, dataDir, `ALTER USER ${DB_USER} PASSWORD '${password}'`);
    if (result.code !== 0 && /lock file|postmaster\.pid|already exists/i.test(result.output)) {
        fs.rmSync(path.join(dataDir, 'postmaster.pid'), { force: true });
        result = await runSql(root, dataDir, `ALTER USER ${DB_USER} PASSWORD '${password}'`);
    }
    if (result.code !== 0) throw failure('setting the database password', result);
}

function serverArgs(dataDir, port, sharedBuffers) {
    return [
        '-D', dataDir,
        '-p', String(port),
        '-c', 'listen_addresses=127.0.0.1',
        '-c', 'unix_socket_directories=',
        '-c', `shared_buffers=${sharedBuffers}`,
        '-c', 'max_connections=60',
        '-c', 'log_min_messages=fatal',
        '-c', 'log_line_prefix=%m ',
    ];
}

function startupPacket() {
    const body = Buffer.from(`user\0${DB_USER}\0database\0${DB_NAME}\0\0`, 'latin1');
    const head = Buffer.alloc(8);
    head.writeInt32BE(body.length + 8, 0);
    head.writeInt32BE(196608, 4);
    return Buffer.concat([head, body]);
}

function probe(port, { host = '127.0.0.1', timeoutMs = 1500 } = {}) {
    return new Promise((resolve) => {
        const sock = new net.Socket();
        let answered = false;
        const done = (state) => {
            if (answered) return;
            answered = true;
            sock.destroy();
            resolve(state);
        };
        sock.setTimeout(timeoutMs, () => done('down'));
        sock.once('error', () => done('down'));
        sock.once('connect', () => sock.write(startupPacket()));
        sock.once('data', (data) => {
            const kind = String.fromCharCode(data[0]);
            if (kind === 'R') return done('ready');
            if (kind === 'E') return done(/C57P0[0-9]/.test(data.toString('latin1')) ? 'starting' : 'ready');
            done('down');
        });
        sock.connect(port, host);
    });
}

async function waitUntilAccepting(port, timeoutMs, isDead = () => false) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (isDead()) throw new Error('PostgreSQL exited before it became ready');
        if (await probe(port) === 'ready') return;
        await new Promise((r) => setTimeout(r, 250));
    }
    throw new Error(`Timeout waiting for PostgreSQL on 127.0.0.1:${port}`);
}

function jdbcUrl(port) {
    return `jdbc:postgresql://127.0.0.1:${port}/${DB_NAME}`;
}

function clearStalePid(dataDir, isAlive = (pid) => { try { process.kill(pid, 0); return true; } catch (_) { return false; } }) {
    const file = path.join(dataDir, 'postmaster.pid');
    const first = readTrimmed(file);
    if (!first) return false;
    const pid = parseInt(first.split('\n')[0], 10);
    if (Number.isInteger(pid) && isAlive(pid)) return false;
    fs.rmSync(file, { force: true });
    return true;
}

module.exports = {
    DB_USER,
    DB_NAME,
    binPath,
    bundledMajor,
    dataMajor,
    clusterState,
    checkVersion,
    buildCluster,
    prepareCluster,
    EMPTY_CLUSTER_DIRS,
    applyPassword,
    serverArgs,
    probe,
    waitUntilAccepting,
    jdbcUrl,
    clearStalePid,
};
