'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { loadDesktopSecrets, ADMIN_PASSWORD_OVERRIDE_ENV } = require('../services/DesktopSecrets');

function memoryStore() {
    const data = new Map();
    return {
        get: (k, d) => (data.has(k) ? data.get(k) : d),
        set: (k, v) => data.set(k, v),
        delete: (k) => data.delete(k),
        data,
    };
}

function fakeSafeStorage({ available = true, backend = 'gnome_libsecret' } = {}) {
    return {
        isEncryptionAvailable: () => available,
        getSelectedStorageBackend: () => backend,
        encryptString: (s) => Buffer.from(`sealed:${s}`),
        decryptString: (b) => {
            const text = b.toString();
            if (!text.startsWith('sealed:')) throw new Error('bad');
            return text.slice(7);
        },
    };
}

test('the first launch generates a 32-byte secret and a long random password and seals them', () => {
    const store = memoryStore();
    const result = loadDesktopSecrets({ store, safeStorage: fakeSafeStorage(), platform: 'win32', env: {} });
    assert.equal(Buffer.from(result.jwtSecret, 'base64').length, 32);
    assert.ok(result.adminPassword.length >= 24);
    assert.equal(result.created, true);
    assert.equal(result.sealed, true);
    assert.ok(store.data.has('desktopSecretsSealed'));
    assert.ok(!store.data.has('desktopSecretsPlain'));
});

test('the second launch returns the same values and does not create new ones', () => {
    const store = memoryStore();
    const safeStorage = fakeSafeStorage();
    const first = loadDesktopSecrets({ store, safeStorage, platform: 'win32', env: {} });
    const second = loadDesktopSecrets({ store, safeStorage, platform: 'win32', env: {} });
    assert.equal(second.created, false);
    assert.equal(second.jwtSecret, first.jwtSecret);
    assert.equal(second.adminPassword, first.adminPassword);
});

test('two different installs never get the same values', () => {
    const a = loadDesktopSecrets({ store: memoryStore(), safeStorage: fakeSafeStorage(), platform: 'win32', env: {} });
    const b = loadDesktopSecrets({ store: memoryStore(), safeStorage: fakeSafeStorage(), platform: 'win32', env: {} });
    assert.notEqual(a.jwtSecret, b.jwtSecret);
    assert.notEqual(a.adminPassword, b.adminPassword);
});

test('without a keychain the values are kept in the store as plain data and reported as not sealed', () => {
    const store = memoryStore();
    const safeStorage = fakeSafeStorage({ available: false });
    const first = loadDesktopSecrets({ store, safeStorage, platform: 'win32', env: {} });
    assert.equal(first.sealed, false);
    assert.ok(store.data.has('desktopSecretsPlain'));
    assert.ok(!store.data.has('desktopSecretsSealed'));
    const second = loadDesktopSecrets({ store, safeStorage, platform: 'win32', env: {} });
    assert.equal(second.created, false);
    assert.equal(second.jwtSecret, first.jwtSecret);
});

test('on Linux the basic_text backend counts as no keychain', () => {
    const store = memoryStore();
    const result = loadDesktopSecrets({
        store, safeStorage: fakeSafeStorage({ backend: 'basic_text' }), platform: 'linux', env: {},
    });
    assert.equal(result.sealed, false);
});

test('values saved while no keychain existed are moved into the keychain once it is available', () => {
    const store = memoryStore();
    const first = loadDesktopSecrets({ store, safeStorage: fakeSafeStorage({ available: false }), platform: 'win32', env: {} });
    const second = loadDesktopSecrets({ store, safeStorage: fakeSafeStorage(), platform: 'win32', env: {} });
    assert.equal(second.jwtSecret, first.jwtSecret);
    assert.ok(store.data.has('desktopSecretsSealed'));
    assert.ok(!store.data.has('desktopSecretsPlain'));
});

test('stored values that can no longer be decrypted are replaced with new ones', () => {
    const store = memoryStore();
    store.set('desktopSecretsSealed', Buffer.from('garbage').toString('base64'));
    const result = loadDesktopSecrets({ store, safeStorage: fakeSafeStorage(), platform: 'win32', env: {} });
    assert.equal(result.created, true);
    assert.equal(Buffer.from(result.jwtSecret, 'base64').length, 32);
});

test('the admin password override applies to the run only and is not stored', () => {
    const store = memoryStore();
    const safeStorage = fakeSafeStorage();
    const overridden = loadDesktopSecrets({
        store, safeStorage, platform: 'win32', env: { [ADMIN_PASSWORD_OVERRIDE_ENV]: 'from-the-test-harness' },
    });
    assert.equal(overridden.adminPassword, 'from-the-test-harness');
    const normal = loadDesktopSecrets({ store, safeStorage, platform: 'win32', env: {} });
    assert.notEqual(normal.adminPassword, 'from-the-test-harness');
});

test('no fixed secret or password is left in the desktop launcher or properties', () => {
    const root = path.join(__dirname, '..', '..');
    const launcher = fs.readFileSync(path.join(root, 'electron-app', 'services', 'ServiceManager.js'), 'utf8');
    const properties = fs.readFileSync(
        path.join(root, 'ontology-desktop', 'src', 'main', 'resources', 'application-desktop.properties'), 'utf8');
    assert.ok(!launcher.includes('b250b2NvZGUtZGVza3RvcC1qd3Qtc2VjcmV0LWtleS12MQ=='));
    assert.ok(!launcher.includes('--jwt.secret='));
    assert.ok(!/^jwt\.secret=/m.test(properties));
    assert.ok(!/^app\.admin\.password=/m.test(properties));
});
