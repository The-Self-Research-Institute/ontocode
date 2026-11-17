'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

const client = require('../services/PgSimpleClient');
const pgElevated = require('../services/PostgresElevated');
const pg = require('../services/PostgresRuntime');

const GOOD_PASSWORD = 'a-valid-password-0123456789';

test('the password handshake matches the published SCRAM-SHA-256 example', () => {
    const result = client.scramClientFinal({
        password: 'pencil',
        clientFirstBare: 'n=user,r=rOprNGfwEbeRWgbNEkqO',
        serverFirst: 'r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096',
    });
    assert.equal(result.clientFinal,
        'c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=');
    assert.equal(result.serverSignature, '6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=');
});

test('the startup message names the user and database and has a correct length', () => {
    const packet = client.startupMessage('ontocode', 'postgres');
    assert.equal(packet.readInt32BE(0), packet.length);
    assert.equal(packet.readInt32BE(4), 196608);
    assert.match(packet.toString('latin1'), /user\0ontocode\0database\0postgres\0/);
});

test('a regular message carries its type and a length that counts itself', () => {
    const packet = client.message('Q', Buffer.from('select 1\0'));
    assert.equal(String.fromCharCode(packet[0]), 'Q');
    assert.equal(packet.readInt32BE(1), packet.length - 1);
});

test('the administrator path can be forced for testing', async () => {
    pgElevated.resetForTests();
    process.env.ONTOCODE_FORCE_ADMIN_PATH = '1';
    try {
        assert.equal(await pgElevated.isElevatedAdmin(), true);
    } finally {
        delete process.env.ONTOCODE_FORCE_ADMIN_PATH;
        pgElevated.resetForTests();
    }
});

test('a server that disappears on its own is reported as a crash', async () => {
    let alive = true;
    const server = new pgElevated.ElevatedServer(4242, { pollMs: 10, alive: () => alive, unref: false });
    const exited = new Promise((resolve) => server.once('exit', resolve));
    alive = false;
    assert.equal(await exited, 1);
    assert.equal(server.exitCode, 1);
});

test('a server that is stopped on purpose is reported as a clean exit', async () => {
    let alive = true;
    const server = new pgElevated.ElevatedServer(4243, { pollMs: 10, alive: () => alive, unref: false });
    server._ontoStopping = true;
    const exited = new Promise((resolve) => server.once('exit', resolve));
    alive = false;
    assert.equal(await exited, 0);
});

test('with administrator rights the password step is skipped and needs no database program', async () => {
    const missingRoot = path.join(os.tmpdir(), 'onto-no-such-postgres-root');
    await assert.doesNotReject(() => pg.applyPassword(missingRoot, path.join(missingRoot, 'data'), GOOD_PASSWORD, { elevated: true }));
});

test('without administrator rights the password step still needs the database program', async () => {
    const missingRoot = path.join(os.tmpdir(), 'onto-no-such-postgres-root');
    await assert.rejects(() => pg.applyPassword(missingRoot, path.join(missingRoot, 'data'), GOOD_PASSWORD));
});

test('a prepared template is used normally but never with administrator rights', async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'onto-pg-root-'));
    const template = path.join(root, 'cluster-template');
    fs.mkdirSync(template, { recursive: true });
    fs.writeFileSync(path.join(template, 'PG_VERSION'), '17');

    const normalDir = path.join(root, 'normal', 'postgres');
    const copied = await pg.prepareCluster(root, normalDir, GOOD_PASSWORD);
    assert.deepEqual(copied, { created: true, fromTemplate: true });

    const elevatedDir = path.join(root, 'elevated', 'postgres');
    await assert.rejects(() => pg.prepareCluster(root, elevatedDir, GOOD_PASSWORD, { elevated: true }));
    assert.equal(fs.existsSync(path.join(elevatedDir, 'PG_VERSION')), false);
});

test('a template next to the original files is used when the runtime was copied without it', async () => {
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'onto-pg-template-'));
    const original = path.join(base, 'original');
    const copied = path.join(base, 'copied');
    fs.mkdirSync(path.join(original, 'cluster-template'), { recursive: true });
    fs.writeFileSync(path.join(original, 'cluster-template', 'PG_VERSION'), '17');
    fs.mkdirSync(copied, { recursive: true });

    const dataDir = path.join(base, 'data', 'postgres');
    const result = await pg.prepareCluster(copied, dataDir, GOOD_PASSWORD, { templateRoot: original });

    assert.deepEqual(result, { created: true, fromTemplate: true });
    assert.equal(fs.readFileSync(path.join(dataDir, 'PG_VERSION'), 'utf8'), '17');
});

test('the Microsoft Store install folder is recognised on Windows only', () => {
    const store = 'C:\\Program Files\\WindowsApps\\SelfResearch.OntocodeStudio_1.2.9.0_x64__5h55vr3cpnr66\\app\\resources\\backend\\postgres\\win32';
    const regular = 'C:\\Program Files\\OntoCode Studio\\resources\\backend\\postgres\\win32';
    assert.equal(pgElevated.isInsideStorePackage(store, 'win32'), true);
    assert.equal(pgElevated.isInsideStorePackage(store.replace(/\\/g, '/'), 'win32'), true);
    assert.equal(pgElevated.isInsideStorePackage(regular, 'win32'), false);
    assert.equal(pgElevated.isInsideStorePackage('C:\\Users\\Someone\\WindowsAppsNotes\\pg', 'win32'), false);
    assert.equal(pgElevated.isInsideStorePackage(store, 'linux'), false);
});

test('the runtime is copied out for administrators and for the Store folder, but not otherwise', () => {
    const store = 'C:\\Program Files\\WindowsApps\\Pkg\\app\\postgres';
    const regular = 'C:\\Program Files\\OntoCode Studio\\postgres';
    assert.equal(pgElevated.needsOwnCopy(false, store, 'win32'), true);
    assert.equal(pgElevated.needsOwnCopy(true, regular, 'win32'), true);
    assert.equal(pgElevated.needsOwnCopy(false, regular, 'win32'), false);
    assert.equal(pgElevated.needsOwnCopy(false, store, 'darwin'), false);
});

const { execFileSync } = require('child_process');

function fakeRuntime(root, exeBytes) {
    const exe = process.platform === 'win32' ? 'postgres.exe' : 'postgres';
    fs.mkdirSync(path.join(root, 'bin'), { recursive: true });
    fs.mkdirSync(path.join(root, 'share'), { recursive: true });
    fs.mkdirSync(path.join(root, 'cluster-template'), { recursive: true });
    fs.writeFileSync(path.join(root, 'bin', exe), exeBytes);
    fs.writeFileSync(path.join(root, 'share', 'postgres.bki'), 'bki');
    fs.writeFileSync(path.join(root, 'cluster-template', 'PG_VERSION'), '17');
    fs.writeFileSync(path.join(root, 'PG_MAJOR'), '17');
}

test('an elevated token is recognised from the integrity level that whoami prints', () => {
    const high = 'Mandatory Label\High Mandatory Level   Label            S-1-16-12288';
    const medium = 'Mandatory Label\Medium Mandatory Level Label            S-1-16-8192';
    const system = 'Mandatory Label\System Mandatory Level Label            S-1-16-16384';
    assert.equal(pgElevated.isHighIntegrity(high), true);
    assert.equal(pgElevated.isHighIntegrity(system), true);
    assert.equal(pgElevated.isHighIntegrity(medium), false);
    assert.equal(pgElevated.isHighIntegrity(''), false);
});

test('the runtime is copied out of the install folder, without the prepared template', async () => {
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'onto-stage-'));
    const root = path.join(base, 'WindowsApps', 'pg');
    const staging = path.join(base, 'data', 'pg-runtime');
    fakeRuntime(root, 'version-one');

    const result = await pgElevated.stageRuntime(root, staging);

    assert.equal(result, staging);
    assert.equal(fs.readFileSync(path.join(staging, 'share', 'postgres.bki'), 'utf8'), 'bki');
    assert.equal(fs.existsSync(path.join(staging, 'PG_MAJOR')), true);
    assert.equal(fs.existsSync(path.join(staging, 'cluster-template')), false);
});

test('a runtime that is already copied is not copied again', async () => {
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'onto-stage-'));
    const root = path.join(base, 'pg');
    const staging = path.join(base, 'data', 'pg-runtime');
    fakeRuntime(root, 'version-one');
    await pgElevated.stageRuntime(root, staging);
    const sentinel = path.join(staging, 'sentinel.txt');
    fs.writeFileSync(sentinel, 'still here');

    await pgElevated.stageRuntime(root, staging);

    assert.equal(fs.existsSync(sentinel), true);
});

test('a new version of the app replaces the old copy', async () => {
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'onto-stage-'));
    const staging = path.join(base, 'data', 'pg-runtime');
    const rootOne = path.join(base, 'pg-1');
    fakeRuntime(rootOne, 'version-one');
    await pgElevated.stageRuntime(rootOne, staging);
    fs.writeFileSync(path.join(staging, 'old-only.txt'), 'old');

    const rootTwo = path.join(base, 'pg-2');
    fakeRuntime(rootTwo, 'a-longer-second-version');
    await pgElevated.stageRuntime(rootTwo, staging);

    assert.equal(fs.existsSync(path.join(staging, 'old-only.txt')), false);
    const exe = process.platform === 'win32' ? 'postgres.exe' : 'postgres';
    assert.equal(fs.readFileSync(path.join(staging, 'bin', exe), 'utf8'), 'a-longer-second-version');
});

test('unfinished copies left by an earlier crash are cleaned up', async () => {
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'onto-stage-'));
    const root = path.join(base, 'pg');
    const staging = path.join(base, 'data', 'pg-runtime');
    fakeRuntime(root, 'version-one');
    const leftover = `${staging}.partial-99999`;
    fs.mkdirSync(leftover, { recursive: true });
    fs.writeFileSync(path.join(leftover, 'half.txt'), 'x');

    await pgElevated.stageRuntime(root, staging);

    assert.equal(fs.existsSync(leftover), false);
});

test('every service file is valid JavaScript, including the ones tests cannot load', () => {
    const dir = path.join(__dirname, '..', 'services');
    const files = fs.readdirSync(dir).filter((name) => name.endsWith('.js'));
    assert.ok(files.length > 5);
    for (const name of files) {
        assert.doesNotThrow(() => execFileSync(process.execPath, ['--check', path.join(dir, name)], { stdio: 'pipe' }), name);
    }
});
