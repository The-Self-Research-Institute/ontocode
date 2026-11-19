'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PIN_FILE = path.join(__dirname, 'postgres-win-arm64.json');
const SHA256 = /^[0-9a-f]{64}$/;

function loadPin(file = PIN_FILE) {
    const pin = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (!pin.version || !SHA256.test(String(pin.sha256 || ''))) {
        throw new Error(`${file} needs a version and a 64-character lower-case sha256`);
    }
    return pin;
}

function resolveSource({ env = process.env, pinFile = PIN_FILE } = {}) {
    const pin = loadPin(pinFile);
    if (env.ONTOCODE_PG_ARM64_FILE) {
        return { kind: 'file', file: env.ONTOCODE_PG_ARM64_FILE, sha256: pin.sha256, version: pin.version };
    }
    if (pin.url) {
        return { kind: 'url', url: pin.url, sha256: pin.sha256, version: pin.version };
    }
    return null;
}

function sha256Of(file) {
    return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function verify(file, expected) {
    const actual = sha256Of(file);
    return { ok: actual === expected, actual };
}

module.exports = { PIN_FILE, loadPin, resolveSource, sha256Of, verify };
