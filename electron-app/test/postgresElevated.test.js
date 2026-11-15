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
