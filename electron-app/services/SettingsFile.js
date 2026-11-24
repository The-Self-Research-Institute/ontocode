'use strict';

const fs = require('fs');
const path = require('path');

const ENCRYPTION_KEY = 'ontocode-desktop-v1';
const SETTINGS_NAME = 'settings';
const LEGACY_NAME = 'config';
const OWNER_ONLY = 0o600;

function openSettings({ dir, create, log = console }) {
    const settingsPath = path.join(dir, `${SETTINGS_NAME}.json`);
    const legacyPath = path.join(dir, `${LEGACY_NAME}.json`);
    const firstStart = !fs.existsSync(settingsPath);
    const store = create(SETTINGS_NAME, { encryptionKey: ENCRYPTION_KEY, configFileMode: OWNER_ONLY });
    if (!firstStart || !fs.existsSync(legacyPath)) return store;
    try {
        const legacy = create(LEGACY_NAME, { encryptionKey: ENCRYPTION_KEY });
        const data = legacy.store;
        if (Object.keys(data).length > 0) store.store = data;
        log.info?.(`[Settings] Copied ${Object.keys(data).length} settings from ${LEGACY_NAME}.json; it is kept unchanged for older versions`);
    } catch (err) {
        log.warn?.(`[Settings] Could not read ${LEGACY_NAME}.json, starting with fresh settings: ${err.message}`);
    }
    return store;
}

module.exports = { openSettings, SETTINGS_NAME, LEGACY_NAME, ENCRYPTION_KEY };
