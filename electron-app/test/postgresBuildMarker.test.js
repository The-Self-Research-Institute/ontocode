'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

const marker = require('../scripts/postgresBuildMarker');

function tempDir() {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'onto-pgmark-'));
}

test('a folder with no marker counts as not prepared', () => {
    assert.equal(marker.matches(tempDir(), marker.label('linux', 'x64', '17.12.0')), false);
});

test('a folder prepared for the same platform, CPU and version is reused', () => {
    const dir = tempDir();
    marker.write(dir, marker.label('linux', 'arm64', '17.12.0'));
    assert.equal(marker.matches(dir, marker.label('linux', 'arm64', '17.12.0')), true);
});

test('a folder prepared for the other CPU type is not reused', () => {
    const dir = tempDir();
    marker.write(dir, marker.label('linux', 'x64', '17.12.0'));
    assert.equal(marker.matches(dir, marker.label('linux', 'arm64', '17.12.0')), false);
});

test('a folder holding an older minor version is not reused', () => {
    const dir = tempDir();
    marker.write(dir, marker.label('win32', 'x64', '17.11.0'));
    assert.equal(marker.matches(dir, marker.label('win32', 'x64', '17.12.0')), false);
});
