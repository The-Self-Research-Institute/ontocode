'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

const upgrade = require('../services/PostgresUpgrade');

const exe = process.platform === 'win32' ? 'postgres.exe' : 'postgres';

function tempDir() {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'onto-pgup-'));
}

function runtime(major) {
    const root = tempDir();
    fs.mkdirSync(path.join(root, 'bin'));
    fs.writeFileSync(path.join(root, 'bin', exe), 'binary');
    fs.writeFileSync(path.join(root, 'PG_MAJOR'), major);
    return root;
}

function userData(major) {
    const home = tempDir();
    const dataDir = path.join(home, 'data', 'postgres');
    fs.mkdirSync(dataDir, { recursive: true });
    fs.writeFileSync(path.join(dataDir, 'PG_VERSION'), major);
    fs.writeFileSync(path.join(dataDir, 'projects.dat'), 'the user data');
    return { home, dataDir, logsDir: path.join(home, 'logs') };
}

function fakeDeps(overrides = {}) {
    const calls = { started: [], stopped: [], converter: [] };
    let port = 50000;
    const deps = {
        freeBytes: () => 100 * 1024 * 1024 * 1024,
        folderBytes: () => 1000,
        freePort: async () => ++port,
        applyPassword: async () => {},
        startServer: async ({ dataDir }) => { calls.started.push(dataDir); },
        stopServer: async (root, dataDir) => { calls.stopped.push(dataDir); },
        buildCluster: async (root, dir) => {
            fs.mkdirSync(dir, { recursive: true });
            fs.writeFileSync(path.join(dir, 'PG_VERSION'), '19');
        },
        runConverter: async (args) => {
            calls.converter.push(args);
            fs.writeFileSync(path.join(path.dirname(args.logFile), 'converted.marker'), 'yes');
            return 0;
        },
        now: () => new Date(2026, 10, 25, 9, 30, 15),
        ...overrides,
    };
    return { deps, calls };
}

function runWith(user, deps) {
    return upgrade.run({
        dataDir: user.dataDir, bundledRoot: runtime('19'), legacyRoot: runtime('17'), password: 'p'.repeat(24),
        javaBin: 'java', desktopJar: 'desktop.jar', sharedBuffers: '64MB', logsDir: user.logsDir,
        from: '17', to: '19', deps,
    });
}

test('a data folder from the same major version just starts', () => {
    const user = userData('19');
    assert.deepEqual(upgrade.decide({ dataDir: user.dataDir, bundledRoot: runtime('19'), legacyRoot: runtime('17') }), { action: 'start' });
});

test('a new install with no data folder just starts', () => {
    const missing = path.join(tempDir(), 'none');
    assert.deepEqual(upgrade.decide({ dataDir: missing, bundledRoot: runtime('19'), legacyRoot: runtime('17') }), { action: 'start' });
});

test('a 17 data folder is upgraded when the app ships 19 and still carries 17', () => {
    const user = userData('17');
    assert.deepEqual(upgrade.decide({ dataDir: user.dataDir, bundledRoot: runtime('19'), legacyRoot: runtime('17') }),
        { action: 'upgrade', from: '17', to: '19' });
});

test('without the previous engine, or with data from a newer app, the app refuses as before', () => {
    const user17 = userData('17');
    assert.equal(upgrade.decide({ dataDir: user17.dataDir, bundledRoot: runtime('19'), legacyRoot: path.join(tempDir(), 'none') }).action, 'refuse');
    assert.equal(upgrade.decide({ dataDir: user17.dataDir, bundledRoot: runtime('19'), legacyRoot: runtime('16') }).action, 'refuse');
    const user20 = userData('20');
    assert.equal(upgrade.decide({ dataDir: user20.dataDir, bundledRoot: runtime('19'), legacyRoot: runtime('17') }).action, 'refuse');
});

test('a successful upgrade swaps in the new folder and keeps the old one as a dated backup', async () => {
    const user = userData('17');
    const { deps, calls } = fakeDeps();

    const outcome = await runWith(user, deps);

    assert.equal(outcome.ok, true);
    assert.equal(fs.readFileSync(path.join(user.dataDir, 'PG_VERSION'), 'utf8'), '19');
    assert.equal(path.basename(outcome.backupDir), 'postgres-17-backup-20261125-093015');
    assert.equal(fs.readFileSync(path.join(outcome.backupDir, 'projects.dat'), 'utf8'), 'the user data');
    assert.equal(fs.existsSync(upgrade.workDirFor(user.dataDir)), false);
    assert.equal(fs.existsSync(path.join(user.dataDir, upgrade.VERIFIED_MARKER)), false);
    for (const started of calls.started) assert.ok(calls.stopped.includes(started), `${started} was left running`);
    assert.equal(calls.converter[0].env.ONTOCODE_DB_PASSWORD, 'p'.repeat(24));
    assert.match(calls.converter[0].env.ONTOCODE_UPGRADE_SOURCE_URL, /^jdbc:postgresql:\/\/127\.0\.0\.1:\d+\//);
});

test('a failed conversion leaves the old folder exactly as it was and removes the half-made copy', async () => {
    const user = userData('17');
    const before = fs.readFileSync(path.join(user.dataDir, 'projects.dat'), 'utf8');
    const { deps, calls } = fakeDeps({ runConverter: async () => 2 });

    const outcome = await runWith(user, deps);

    assert.equal(outcome.ok, false);
    assert.equal(outcome.reason, 'error');
    assert.equal(fs.readFileSync(path.join(user.dataDir, 'PG_VERSION'), 'utf8'), '17');
    assert.equal(fs.readFileSync(path.join(user.dataDir, 'projects.dat'), 'utf8'), before);
    assert.equal(fs.existsSync(upgrade.workDirFor(user.dataDir)), false);
    for (const started of calls.started) assert.ok(calls.stopped.includes(started), `${started} was left running`);
    assert.equal(fs.readdirSync(path.dirname(user.dataDir)).filter((n) => n.includes('backup')).length, 0);
});

test('when the new server cannot start, the old server is still stopped and nothing is changed', async () => {
    const user = userData('17');
    const { deps, calls } = fakeDeps({
        startServer: async ({ dataDir }) => {
            if (dataDir.endsWith('-upgrade')) throw new Error('port in use');
            calls.started.push(dataDir);
        },
    });

    const outcome = await runWith(user, deps);

    assert.equal(outcome.ok, false);
    assert.deepEqual(calls.stopped, [user.dataDir, user.dataDir]);
    assert.equal(fs.readFileSync(path.join(user.dataDir, 'PG_VERSION'), 'utf8'), '17');
    assert.match(fs.readFileSync(outcome.logFile, 'utf8'), /port in use/);
});

test('too little free disk space postpones the upgrade before anything starts', async () => {
    const user = userData('17');
    const { deps, calls } = fakeDeps({ freeBytes: () => 500 * 1024 * 1024, folderBytes: () => 2 * 1024 * 1024 * 1024 });

    const outcome = await runWith(user, deps);

    assert.equal(outcome.ok, false);
    assert.equal(outcome.reason, 'disk');
    assert.equal(calls.started.length, 0);
    assert.match(upgrade.describe(outcome, outcome.logFile), /about 5 GB of free disk space/);
});

test('a copy left behind by an interrupted upgrade is cleared and the upgrade runs again', async () => {
    const user = userData('17');
    const leftover = upgrade.workDirFor(user.dataDir);
    fs.mkdirSync(leftover, { recursive: true });
    fs.writeFileSync(path.join(leftover, 'half-written'), 'x');
    const { deps, calls } = fakeDeps();

    const outcome = await runWith(user, deps);

    assert.equal(outcome.ok, true);
    assert.equal(fs.existsSync(path.join(user.dataDir, 'half-written')), false);
    assert.equal(calls.stopped[0], leftover);
});

test('a verified copy that was interrupted while being switched in is finished at the next start', () => {
    const user = userData('17');
    const workDir = upgrade.workDirFor(user.dataDir);
    fs.mkdirSync(workDir);
    fs.writeFileSync(path.join(workDir, 'PG_VERSION'), '19');
    fs.writeFileSync(path.join(workDir, upgrade.VERIFIED_MARKER), '17');
    fs.renameSync(user.dataDir, `${user.dataDir}-17-backup-20261125-093015`);

    assert.equal(upgrade.recover({ dataDir: user.dataDir }), true);
    assert.equal(fs.readFileSync(path.join(user.dataDir, 'PG_VERSION'), 'utf8'), '19');
    assert.equal(fs.existsSync(path.join(user.dataDir, upgrade.VERIFIED_MARKER)), false);
    assert.equal(fs.existsSync(workDir), false);
});

test('an unverified leftover copy is never switched in, and a present data folder is never replaced', () => {
    const user = userData('17');
    const workDir = upgrade.workDirFor(user.dataDir);
    fs.mkdirSync(workDir);
    fs.writeFileSync(path.join(workDir, 'PG_VERSION'), '19');

    assert.equal(upgrade.recover({ dataDir: user.dataDir }), false);
    fs.writeFileSync(path.join(workDir, upgrade.VERIFIED_MARKER), '17');
    assert.equal(upgrade.recover({ dataDir: user.dataDir }), false);
    assert.equal(fs.readFileSync(path.join(user.dataDir, 'PG_VERSION'), 'utf8'), '17');

    fs.rmSync(user.dataDir, { recursive: true });
    fs.rmSync(path.join(workDir, upgrade.VERIFIED_MARKER));
    assert.equal(upgrade.recover({ dataDir: user.dataDir }), false);
    assert.equal(fs.existsSync(user.dataDir), false);
});

test('the two temporary servers write separate log files, apart from the upgrade log', async () => {
    const user = userData('17');
    const logs = [];
    const { deps } = fakeDeps({ startServer: async ({ logFile }) => { logs.push(path.basename(logFile)); } });

    const outcome = await runWith(user, deps);

    assert.equal(outcome.ok, true);
    assert.deepEqual(logs, ['database-upgrade-postgres17.log', 'database-upgrade-postgres19.log']);
    assert.equal(path.basename(outcome.logFile), 'database-upgrade.log');
});

test('an unexpected error anywhere in the upgrade is reported, never thrown, and the old folder stays', async () => {
    const user = userData('17');
    const { deps } = fakeDeps({ folderBytes: () => { throw new Error('folder vanished'); } });

    const outcome = await runWith(user, deps);

    assert.equal(outcome.ok, false);
    assert.match(outcome.message, /folder vanished/);
    assert.equal(fs.readFileSync(path.join(user.dataDir, 'PG_VERSION'), 'utf8'), '17');
});

test('a log file that cannot be written does not turn a failed upgrade into a failed app start', async () => {
    const user = userData('17');
    fs.mkdirSync(path.join(user.logsDir, 'database-upgrade.log'), { recursive: true });
    const { deps } = fakeDeps({ buildCluster: async () => { throw new Error('initdb failed'); } });

    const outcome = await runWith(user, deps);

    assert.equal(outcome.ok, false);
    assert.equal(outcome.reason, 'error');
    assert.equal(fs.readFileSync(path.join(user.dataDir, 'PG_VERSION'), 'utf8'), '17');
    assert.equal(fs.existsSync(upgrade.workDirFor(user.dataDir)), false);
});

test('a copy left by an earlier attempt is stopped and removed once the database is already current', async () => {
    const user = userData('19');
    const workDir = upgrade.workDirFor(user.dataDir);
    fs.mkdirSync(workDir);
    fs.writeFileSync(path.join(workDir, 'PG_VERSION'), '19');
    const stopped = [];

    const removed = await upgrade.discardLeftover({
        dataDir: user.dataDir, bundledRoot: 'root', stopServer: async (root, dir) => { stopped.push(dir); },
    });

    assert.equal(removed, true);
    assert.deepEqual(stopped, [workDir]);
    assert.equal(fs.existsSync(workDir), false);
    assert.equal(fs.readFileSync(path.join(user.dataDir, 'projects.dat'), 'utf8'), 'the user data');
});

test('nothing is removed when there is no leftover copy or no live database', async () => {
    const user = userData('19');
    assert.equal(await upgrade.discardLeftover({ dataDir: user.dataDir, bundledRoot: 'root', stopServer: async () => {} }), false);
    const workDir = upgrade.workDirFor(user.dataDir);
    fs.mkdirSync(workDir);
    fs.rmSync(user.dataDir, { recursive: true });
    assert.equal(await upgrade.discardLeftover({ dataDir: user.dataDir, bundledRoot: 'root', stopServer: async () => {} }), false);
    assert.equal(fs.existsSync(workDir), true);
});

test('the notice for a failed upgrade says the data is unchanged and where the details are', () => {
    const text = upgrade.describe({ reason: 'error' }, 'C:/logs/database-upgrade.log');
    assert.match(text, /data is unchanged/);
    assert.match(text, /database-upgrade\.log/);
});
