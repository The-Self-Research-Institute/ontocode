'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const Conf = require('conf').default;

const WRITTEN_BY_ELECTRON_STORE_8 = 'LROlEBwmqReP7FOKLK/TbDrPVZbf1Y4gDMZw8b4iD4G0twsyEq4Wyy0WaG9MZeLMCYOTOgN/ykJRhLYz5n8/ph+1NdM3TJZmdJF+SfWfPDPilTXHJydySR6UYW+AF5eC1fbj+vsVlFKvo3NEISDzVvpRCFfDtQOB9fkOFiOS3xI2mS8y4qPoYxTpMyz5WGffhGeeHRoY7l7z0dyYvQ6hMQXK/7ojhOOTox2jNOFx+LEdOhtyNQb4OGGmqGrExVJUq0YdUORN4l9iqlTbroNWjvXcuztquw4CfI1aFGAEy/tEfwu4mrM5FVeBeORGyVakoFpdMPnFrObi3BcdILrqZEIzVOls5xKN7rsWfg9BGdkFZggPIz4IGRwUVdBe88DwcQKbTDJQKUi/WdCX7P9aTpgqLiKZsVrFdlTY1chZR0okCI2C+e2i9EMPxGqRTxB66KR4tNAwgnCD2Fr3rymZDjs=';

test('settings saved by desktop builds that used electron-store 8 still load', (t) => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ontocode-settings-'));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    fs.writeFileSync(path.join(dir, 'config.json'), Buffer.from(WRITTEN_BY_ELECTRON_STORE_8, 'base64'));

    const store = new Conf({ cwd: dir, configName: 'config', encryptionKey: 'ontocode-desktop-v1' });

    assert.equal(store.get('authToken'), 'old-token');
    assert.equal(store.get('deviceId'), '7b0d3c9e-6f1a-4c2b-9d8e-123456789abc');
    assert.deepEqual(store.get('localProfile'), { displayName: 'Old User' });
    assert.deepEqual(store.get('desktopSecretsPlain'), {
        jwtSecret: 'a'.repeat(44), adminPassword: 'b'.repeat(24), dbPassword: 'c'.repeat(32),
    });

    store.set('authToken', 'new-token');
    const reopened = new Conf({ cwd: dir, configName: 'config', encryptionKey: 'ontocode-desktop-v1' });
    assert.equal(reopened.get('authToken'), 'new-token');
    assert.equal(reopened.get('deviceId'), '7b0d3c9e-6f1a-4c2b-9d8e-123456789abc');
});

const { openSettings } = require('../services/SettingsFile');

const quiet = { info() {}, warn() {} };
const opener = (dir) => (name, options) => new Conf({ cwd: dir, configName: name, ...options });

function tempDir(t) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ontocode-settings-'));
    t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
    return dir;
}

test('the first start copies the old settings into settings.json and leaves config.json untouched', (t) => {
    const dir = tempDir(t);
    const legacyBytes = Buffer.from(WRITTEN_BY_ELECTRON_STORE_8, 'base64');
    fs.writeFileSync(path.join(dir, 'config.json'), legacyBytes);

    const store = openSettings({ dir, create: opener(dir), log: quiet });

    assert.equal(path.basename(store.path), 'settings.json');
    assert.equal(store.get('authToken'), 'old-token');
    assert.equal(store.get('deviceId'), '7b0d3c9e-6f1a-4c2b-9d8e-123456789abc');
    assert.deepEqual(store.get('localProfile'), { displayName: 'Old User' });
    assert.deepEqual(store.get('desktopSecretsPlain'), {
        jwtSecret: 'a'.repeat(44), adminPassword: 'b'.repeat(24), dbPassword: 'c'.repeat(32),
    });
    store.set('authToken', 'new-token');
    assert.deepEqual(fs.readFileSync(path.join(dir, 'config.json')), legacyBytes);
});

test('an older version can still open its own config.json after the new version has saved settings', (t) => {
    const dir = tempDir(t);
    fs.writeFileSync(path.join(dir, 'config.json'), Buffer.from(WRITTEN_BY_ELECTRON_STORE_8, 'base64'));

    openSettings({ dir, create: opener(dir), log: quiet }).set('authToken', 'set-by-new-version');
    const asOlderVersion = new Conf({ cwd: dir, configName: 'config', encryptionKey: 'ontocode-desktop-v1' });

    assert.equal(asOlderVersion.get('authToken'), 'old-token');
    assert.equal(asOlderVersion.get('deviceId'), '7b0d3c9e-6f1a-4c2b-9d8e-123456789abc');
});

test('later starts keep settings.json and do not copy config.json again', (t) => {
    const dir = tempDir(t);
    fs.writeFileSync(path.join(dir, 'config.json'), Buffer.from(WRITTEN_BY_ELECTRON_STORE_8, 'base64'));
    openSettings({ dir, create: opener(dir), log: quiet }).set('authToken', 'kept');
    new Conf({ cwd: dir, configName: 'config', encryptionKey: 'ontocode-desktop-v1' }).set('authToken', 'written-later-by-old-version');

    const store = openSettings({ dir, create: opener(dir), log: quiet });

    assert.equal(store.get('authToken'), 'kept');
});

test('a fresh install starts empty and does not create config.json', (t) => {
    const dir = tempDir(t);

    const store = openSettings({ dir, create: opener(dir), log: quiet });
    store.set('deviceId', 'fresh');

    assert.equal(store.get('deviceId'), 'fresh');
    assert.equal(fs.existsSync(path.join(dir, 'config.json')), false);
});

test('an unreadable config.json does not stop the app and is left in place', (t) => {
    const dir = tempDir(t);
    const garbage = Buffer.from('this is not a settings file');
    fs.writeFileSync(path.join(dir, 'config.json'), garbage);
    const warnings = [];

    const store = openSettings({ dir, create: opener(dir), log: { info() {}, warn: (m) => warnings.push(m) } });
    store.set('deviceId', 'after-failure');

    assert.equal(store.get('deviceId'), 'after-failure');
    assert.equal(warnings.length, 1);
    assert.deepEqual(fs.readFileSync(path.join(dir, 'config.json')), garbage);
});

test('settings.json is created readable by its owner only', { skip: process.platform === 'win32' }, (t) => {
    const dir = tempDir(t);

    const store = openSettings({ dir, create: opener(dir), log: quiet });
    store.set('deviceId', 'x');

    assert.equal(fs.statSync(store.path).mode & 0o777, 0o600);
});
