'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const rt = require('../services/PostgresRuntime');

function tempDir() {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'pgrt-'));
}

test('a missing or empty data folder counts as missing, a folder with PG_VERSION as present', () => {
    const dir = tempDir();
    assert.equal(rt.clusterState(path.join(dir, 'nope')), 'missing');
    assert.equal(rt.clusterState(dir), 'missing');
    fs.writeFileSync(path.join(dir, 'PG_VERSION'), '17\n');
    assert.equal(rt.clusterState(dir), 'present');
});

test('a folder with files but no PG_VERSION is reported as incomplete', () => {
    const dir = tempDir();
    fs.writeFileSync(path.join(dir, 'leftover'), 'x');
    assert.equal(rt.clusterState(dir), 'incomplete');
});

test('data from another PostgreSQL major version is refused, same or unknown versions pass', () => {
    const root = tempDir();
    const data = tempDir();
    fs.writeFileSync(path.join(root, 'PG_MAJOR'), '17');
    assert.deepEqual(rt.checkVersion(root, data), { ok: true, dataMajor: null, bundledMajor: '17' });
    fs.writeFileSync(path.join(data, 'PG_VERSION'), '16\n');
    assert.equal(rt.checkVersion(root, data).ok, false);
    assert.equal(rt.checkVersion(root, data).dataMajor, '16');
    fs.writeFileSync(path.join(data, 'PG_VERSION'), '17\n');
    assert.equal(rt.checkVersion(root, data).ok, true);
});

test('a leftover postmaster.pid of a dead process is removed, one of a live process is kept', () => {
    const data = tempDir();
    const file = path.join(data, 'postmaster.pid');
    fs.writeFileSync(file, '4242\n/data\n');
    assert.equal(rt.clearStalePid(data, () => false), true);
    assert.equal(fs.existsSync(file), false);
    fs.writeFileSync(file, '4242\n/data\n');
    assert.equal(rt.clearStalePid(data, () => true), false);
    assert.equal(fs.existsSync(file), true);
    assert.equal(rt.clearStalePid(tempDir()), false);
});

test('the server is started on the loopback address only, without a unix socket', () => {
    const args = rt.serverArgs('/data', 15432, '192MB');
    assert.ok(args.includes('listen_addresses=127.0.0.1'));
    assert.ok(args.includes('unix_socket_directories='));
    assert.ok(args.includes('shared_buffers=192MB'));
    assert.equal(args[args.indexOf('-p') + 1], '15432');
});

function fakeServer(reply) {
    return new Promise((resolve) => {
        const server = net.createServer((socket) => {
            socket.once('data', () => socket.end(reply));
        });
        server.listen(0, '127.0.0.1', () => resolve(server));
    });
}

function errorMessage(code) {
    const body = Buffer.from(`SFATAL\0C${code}\0Mx\0\0`, 'latin1');
    const head = Buffer.alloc(5);
    head.write('E', 0, 'latin1');
    head.writeInt32BE(body.length + 4, 1);
    return Buffer.concat([head, body]);
}

test('the readiness probe says ready when the server asks for authentication', async () => {
    const server = await fakeServer(Buffer.from([0x52, 0, 0, 0, 8, 0, 0, 0, 10]));
    try {
        assert.equal(await rt.probe(server.address().port), 'ready');
    } finally { server.close(); }
});

test('the readiness probe says starting while the server reports it is still starting up', async () => {
    const server = await fakeServer(errorMessage('57P03'));
    try {
        assert.equal(await rt.probe(server.address().port), 'starting');
    } finally { server.close(); }
});

test('the readiness probe says down when nothing listens', async () => {
    const server = await fakeServer(Buffer.alloc(0));
    const port = server.address().port;
    await new Promise((r) => server.close(r));
    assert.equal(await rt.probe(port), 'down');
});

test('waiting for a server that is already dead fails at once', async () => {
    await assert.rejects(rt.waitUntilAccepting(1, 5000, () => true), /exited before/);
});

test('passwords that are not plain URL-safe text are rejected before they reach SQL', async () => {
    await assert.rejects(rt.applyPassword(tempDir(), tempDir(), "x'; drop table y; --"), /unsupported format/);
});

function bundleWithTemplate(major) {
    const root = tempDir();
    fs.mkdirSync(path.join(root, 'cluster-template', 'base'), { recursive: true });
    fs.writeFileSync(path.join(root, 'cluster-template', 'PG_VERSION'), `${major}\n`);
    fs.writeFileSync(path.join(root, 'PG_MAJOR'), `${major}\n`);
    return root;
}

function assertAllEmptyDirsExist(dataDir) {
    for (const dir of rt.EMPTY_CLUSTER_DIRS) {
        assert.ok(fs.statSync(path.join(dataDir, dir)).isDirectory(), `${dir} should exist`);
    }
}

test('a new database copied from a template that lost its empty folders gets them back', async () => {
    const root = bundleWithTemplate('17');
    const dataDir = path.join(tempDir(), 'postgres');

    const result = await rt.prepareCluster(root, dataDir, 'unused-password-0000');

    assert.equal(result.created, true);
    assertAllEmptyDirsExist(dataDir);
    assert.ok(fs.existsSync(path.join(dataDir, 'pg_notify')));
});

test('an existing database that is missing its empty folders is repaired on the next start', async () => {
    const root = bundleWithTemplate('17');
    const dataDir = tempDir();
    fs.writeFileSync(path.join(dataDir, 'PG_VERSION'), '17\n');

    const result = await rt.prepareCluster(root, dataDir, 'unused-password-0000');

    assert.equal(result.created, false);
    assertAllEmptyDirsExist(dataDir);
});

const posixOnly = { skip: process.platform === 'win32' && 'file modes do not apply on Windows' };

test('a database copied from the template is private to its owner, as PostgreSQL requires', posixOnly, async () => {
    const root = bundleWithTemplate('17');
    fs.chmodSync(path.join(root, 'cluster-template'), 0o755);
    fs.chmodSync(path.join(root, 'cluster-template', 'PG_VERSION'), 0o644);
    const dataDir = path.join(tempDir(), 'postgres');

    await rt.prepareCluster(root, dataDir, 'unused-password-0000');

    assert.equal(fs.statSync(dataDir).mode & 0o777, 0o700);
    assert.equal(fs.statSync(path.join(dataDir, 'base')).mode & 0o777, 0o700);
    assert.equal(fs.statSync(path.join(dataDir, 'pg_notify')).mode & 0o777, 0o700);
    assert.equal(fs.statSync(path.join(dataDir, 'PG_VERSION')).mode & 0o777, 0o600);
});

test('an existing database folder with open permissions is tightened on the next start', posixOnly, async () => {
    const root = bundleWithTemplate('17');
    const dataDir = tempDir();
    fs.writeFileSync(path.join(dataDir, 'PG_VERSION'), '17\n');
    fs.chmodSync(dataDir, 0o755);

    await rt.prepareCluster(root, dataDir, 'unused-password-0000');

    assert.equal(fs.statSync(dataDir).mode & 0o777, 0o700);
});

test('a database from another PostgreSQL major version is left untouched', async () => {
    const root = bundleWithTemplate('17');
    const dataDir = tempDir();
    fs.writeFileSync(path.join(dataDir, 'PG_VERSION'), '16\n');

    await rt.prepareCluster(root, dataDir, 'unused-password-0000');

    assert.equal(fs.existsSync(path.join(dataDir, 'pg_notify')), false);
});

test('the list of restored folders matches the empty folders of a freshly built cluster', () => {
    const expected = [
        'pg_commit_ts', 'pg_dynshmem', 'pg_logical/mappings', 'pg_logical/snapshots', 'pg_notify',
        'pg_replslot', 'pg_serial', 'pg_snapshots', 'pg_stat_tmp', 'pg_tblspc', 'pg_twophase',
        'pg_wal/archive_status', 'pg_wal/summaries',
    ];
    assert.deepEqual([...rt.EMPTY_CLUSTER_DIRS].sort(), expected.sort());
});
