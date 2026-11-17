'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

const pgArm64 = require('../scripts/pgArm64Source');
const vcRuntime = require('../scripts/vcRuntime');

function tempDir() {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'onto-arm64-'));
}

function writePin(dir, pin) {
    const file = path.join(dir, 'pin.json');
    fs.writeFileSync(file, JSON.stringify(pin));
    return file;
}

const GOOD_SHA = 'a'.repeat(64);

test('the pinned ARM64 PostgreSQL has a version and a 64 character checksum', () => {
    const pin = pgArm64.loadPin();
    assert.equal(pin.version, '17.11');
    assert.match(pin.sha256, /^[0-9a-f]{64}$/);
});

test('a pin without a proper checksum is refused', () => {
    const dir = tempDir();
    assert.throws(() => pgArm64.loadPin(writePin(dir, { version: '17.11', sha256: 'not-a-checksum' })), /sha256/);
    assert.throws(() => pgArm64.loadPin(writePin(dir, { sha256: GOOD_SHA })), /version/);
});

test('a local file named in the environment wins over a download link', () => {
    const pinFile = writePin(tempDir(), { version: '17.11', sha256: GOOD_SHA, url: 'https://example.invalid/pg.zip' });
    const source = pgArm64.resolveSource({ env: { ONTOCODE_PG_ARM64_FILE: 'C:\\pg.zip' }, pinFile });
    assert.deepEqual(source, { kind: 'file', file: 'C:\\pg.zip', sha256: GOOD_SHA, version: '17.11' });
});

test('with no local file the download link in the pin is used', () => {
    const pinFile = writePin(tempDir(), { version: '17.11', sha256: GOOD_SHA, url: 'https://example.invalid/pg.zip' });
    const source = pgArm64.resolveSource({ env: {}, pinFile });
    assert.equal(source.kind, 'url');
    assert.equal(source.url, 'https://example.invalid/pg.zip');
});

test('with no local file and no link there is no source', () => {
    const pinFile = writePin(tempDir(), { version: '17.11', sha256: GOOD_SHA, url: null });
    assert.equal(pgArm64.resolveSource({ env: {}, pinFile }), null);
});

test('a file is accepted only when its checksum matches the pin', () => {
    const dir = tempDir();
    const file = path.join(dir, 'pg.zip');
    fs.writeFileSync(file, 'pretend zip');
    const real = crypto.createHash('sha256').update('pretend zip').digest('hex');
    assert.equal(pgArm64.verify(file, real).ok, true);
    const wrong = pgArm64.verify(file, GOOD_SHA);
    assert.equal(wrong.ok, false);
    assert.equal(wrong.actual, real);
});

test('ARM64 needs only the one runtime file that PostgreSQL imports, x64 needs three', () => {
    assert.deepEqual(vcRuntime.requiredFor('arm64'), ['vcruntime140.dll']);
    assert.deepEqual(vcRuntime.requiredFor('x64'), ['vcruntime140.dll', 'vcruntime140_1.dll', 'msvcp140.dll']);
    assert.deepEqual(vcRuntime.requiredFor(undefined), vcRuntime.VC_RUNTIME_DLLS);
});

test('a missing runtime file is judged against the list for the chosen architecture', () => {
    const dir = tempDir();
    fs.writeFileSync(path.join(dir, 'vcruntime140.dll'), 'x');
    assert.deepEqual(vcRuntime.missingVcRuntime(dir, vcRuntime.requiredFor('arm64')), []);
    assert.deepEqual(vcRuntime.missingVcRuntime(dir, vcRuntime.requiredFor('x64')), ['vcruntime140_1.dll', 'msvcp140.dll']);
});

test('the ARM64 Store script prepares ARM64 files first and builds an ARM64 package', () => {
    const scripts = require('../package.json').scripts;
    const script = scripts['dist:win:appx:arm64'];
    assert.match(script, /prepare-resources -- --arch=arm64/);
    assert.match(script, /electron-builder --win appx --arm64/);
    assert.doesNotMatch(scripts['dist:win:appx'], /arm64/);
});

test('the ARM64 installer script builds an installer whose name says arm64', () => {
    const script = require('../package.json').scripts['dist:win:arm64'];
    assert.match(script, /prepare-resources -- --arch=arm64/);
    assert.match(script, /electron-builder --win nsis --arm64/);
    assert.match(script, /Setup \$\{version\} arm64\./);
});
