'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

const jreModules = require('../scripts/jreModules');

function jreWith(modules) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'onto-jremods-'));
    if (modules !== null) {
        fs.writeFileSync(path.join(dir, 'release'), `JAVA_VERSION="21.0.11"\nMODULES="${modules.join(' ')}"\nOS_NAME="Windows"\n`);
    }
    return dir;
}

test('a Java runtime with every required module is complete', () => {
    const dir = jreWith(['java.base', 'jdk.net', 'jdk.zipfs']);
    assert.deepEqual(jreModules.missingModules(dir, ['java.base', 'jdk.net']), []);
});

test('a Java runtime made before jdk.net was added is reported as missing it', () => {
    const dir = jreWith(['java.base', 'java.net.http', 'jdk.zipfs']);
    assert.deepEqual(jreModules.missingModules(dir, ['java.base', 'jdk.net', 'jdk.zipfs']), ['jdk.net']);
});

test('a Java runtime whose module list cannot be read counts as missing everything', () => {
    const dir = jreWith(null);
    assert.deepEqual(jreModules.missingModules(dir, ['java.base', 'jdk.net']), ['java.base', 'jdk.net']);
});

test('the module names are read from the release file', () => {
    const dir = jreWith(['java.base', 'jdk.net']);
    assert.deepEqual(jreModules.installedModules(dir), ['java.base', 'jdk.net']);
});
