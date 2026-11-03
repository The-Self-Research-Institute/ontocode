'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');

const SEALED_KEY = 'desktopSecretsSealed';
const PLAIN_KEY = 'desktopSecretsPlain';
const ADMIN_PASSWORD_OVERRIDE_ENV = 'ONTOCODE_DESKTOP_ADMIN_PASSWORD';

function generateSecrets() {
    return {
        jwtSecret: crypto.randomBytes(32).toString('base64'),
        adminPassword: crypto.randomBytes(24).toString('base64url'),
    };
}

function isValid(secrets) {
    return !!secrets
        && typeof secrets.jwtSecret === 'string' && secrets.jwtSecret.length >= 44
        && typeof secrets.adminPassword === 'string' && secrets.adminPassword.length >= 16;
}

function sealingAvailable(safeStorage, platform) {
    if (!safeStorage.isEncryptionAvailable()) return false;
    if (platform !== 'linux') return true;
    return safeStorage.getSelectedStorageBackend() !== 'basic_text';
}

function restrictToOwner(store, platform) {
    if (platform === 'win32' || !store.path) return;
    try { fs.chmodSync(store.path, 0o600); } catch {}
}

function readStored(store, safeStorage, sealing) {
    const sealed = store.get(SEALED_KEY, null);
    if (sealed && sealing) {
        try {
            const parsed = JSON.parse(safeStorage.decryptString(Buffer.from(sealed, 'base64')));
            if (isValid(parsed)) return parsed;
        } catch {}
        store.delete(SEALED_KEY);
    }
    const plain = store.get(PLAIN_KEY, null);
    return isValid(plain) ? plain : null;
}

function writeStored(store, safeStorage, sealing, platform, secrets) {
    if (sealing) {
        store.set(SEALED_KEY, safeStorage.encryptString(JSON.stringify(secrets)).toString('base64'));
        store.delete(PLAIN_KEY);
        return;
    }
    store.delete(SEALED_KEY);
    store.set(PLAIN_KEY, secrets);
    restrictToOwner(store, platform);
}

function loadDesktopSecrets({ store, safeStorage, platform = process.platform, env = process.env }) {
    const sealing = sealingAvailable(safeStorage, platform);
    let secrets = readStored(store, safeStorage, sealing);
    const created = !secrets;
    if (created) secrets = generateSecrets();
    if (created || (sealing && store.get(PLAIN_KEY, null))) {
        writeStored(store, safeStorage, sealing, platform, secrets);
    }
    const override = env[ADMIN_PASSWORD_OVERRIDE_ENV];
    if (override) secrets = { ...secrets, adminPassword: override };
    return { ...secrets, sealed: sealing, created };
}

module.exports = {
    loadDesktopSecrets,
    generateSecrets,
    ADMIN_PASSWORD_OVERRIDE_ENV,
};
