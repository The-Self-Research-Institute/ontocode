'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

const vcRuntime = require('../scripts/vcRuntime');
const pg = require('../services/PostgresRuntime');

function tempDir() {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'onto-vc-'));
}

function touch(dir, name) {
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, name), 'dll');
}

test('the runtime files are copied next to postgres.exe from the first folder that has them', () => {
    const root = tempDir();
    const bin = path.join(root, 'pg', 'bin');
    const jre = path.join(root, 'jre', 'bin');
    const jre17 = path.join(root, 'jre17', 'bin');
    fs.mkdirSync(bin, { recursive: true });
    vcRuntime.VC_RUNTIME_DLLS.forEach((n) => touch(jre, n));
    touch(jre17, 'vcruntime140.dll');

    const result = vcRuntime.bundleVcRuntime(bin, [jre, jre17]);

    assert.deepEqual(result.copied.sort(), [...vcRuntime.VC_RUNTIME_DLLS].sort());
    assert.deepEqual(result.missing, []);
    assert.deepEqual(vcRuntime.missingVcRuntime(bin), []);
});

test('files that are already there are left alone and a missing source is reported', () => {
    const root = tempDir();
    const bin = path.join(root, 'bin');
    const jre = path.join(root, 'jre');
    touch(bin, 'vcruntime140.dll');
    fs.writeFileSync(path.join(bin, 'vcruntime140.dll'), 'original');
    touch(jre, 'vcruntime140.dll');
    touch(jre, 'msvcp140.dll');

    const result = vcRuntime.bundleVcRuntime(bin, [jre]);

    assert.deepEqual(result.present, ['vcruntime140.dll']);
    assert.deepEqual(result.copied, ['msvcp140.dll']);
    assert.deepEqual(result.missing, ['vcruntime140_1.dll']);
    assert.equal(fs.readFileSync(path.join(bin, 'vcruntime140.dll'), 'utf8'), 'original');
    assert.deepEqual(vcRuntime.missingVcRuntime(bin), ['vcruntime140_1.dll']);
});

test('the Windows "library not found" exit code gets a clear message in the password step', () => {
    const error = pg.failure('setting the database password', { code: 3221225781, output: '' });
    assert.match(error.message, /exit 3221225781/);
    assert.match(error.message, /Visual C\+\+/);
    assert.match(error.message, /Redistributable/);
});

test('other failures keep their own message without the Windows hint', () => {
    const error = pg.failure('setting the database password', { code: 1, output: 'FATAL: something else\n' });
    assert.match(error.message, /exit 1/);
    assert.match(error.message, /something else/);
    assert.doesNotMatch(error.message, /Visual C\+\+/);
});

test('only the Windows "library not found" code is recognised, also as an unsigned or signed number', () => {
    assert.equal(pg.isDllNotFound(3221225781), true);
    assert.equal(pg.isDllNotFound(-1073741515), true);
    assert.equal(pg.isDllNotFound(1), false);
    assert.equal(pg.isDllNotFound(null), false);
});

test('the installer never packs the old MongoDB folder', () => {
    const builder = fs.readFileSync(path.join(__dirname, '..', 'electron-builder.yml'), 'utf8');
    assert.match(builder, /"!mongodb\/\*\*"/);
});
