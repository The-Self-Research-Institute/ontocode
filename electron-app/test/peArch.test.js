'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

const peArch = require('../scripts/peArch');

function fakePe(file, machine) {
    const buf = Buffer.alloc(256);
    buf.writeUInt16LE(0x5a4d, 0);
    buf.writeUInt32LE(0x80, 0x3c);
    buf.writeUInt32LE(0x00004550, 0x80);
    buf.writeUInt16LE(machine, 0x84);
    fs.writeFileSync(file, buf);
}

function tempDir() {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'onto-pe-'));
}

test('the processor type is read from the program header', () => {
    const dir = tempDir();
    fakePe(path.join(dir, 'a.exe'), 0x8664);
    fakePe(path.join(dir, 'b.exe'), 0xaa64);
    fakePe(path.join(dir, 'c.dll'), 0x14c);
    assert.equal(peArch.peMachine(path.join(dir, 'a.exe')), 'x64');
    assert.equal(peArch.peMachine(path.join(dir, 'b.exe')), 'arm64');
    assert.equal(peArch.peMachine(path.join(dir, 'c.dll')), 'x86');
});

test('a file that is not a Windows program has no processor type', () => {
    const dir = tempDir();
    fs.writeFileSync(path.join(dir, 'notes.dll'), 'just text');
    fs.writeFileSync(path.join(dir, 'tiny.exe'), 'MZ');
    assert.equal(peArch.peMachine(path.join(dir, 'notes.dll')), null);
    assert.equal(peArch.peMachine(path.join(dir, 'tiny.exe')), null);
    assert.equal(peArch.peMachine(path.join(dir, 'missing.exe')), null);
});

test('a program of the wrong processor type is reported, in any sub folder', () => {
    const dir = tempDir();
    fs.mkdirSync(path.join(dir, 'bin', 'deep'), { recursive: true });
    fakePe(path.join(dir, 'bin', 'postgres.exe'), 0xaa64);
    fakePe(path.join(dir, 'bin', 'deep', 'old.dll'), 0x8664);
    fs.writeFileSync(path.join(dir, 'bin', 'readme.txt'), 'x');

    const wrong = peArch.findArchMismatches([path.join(dir, 'bin')], 'arm64');

    assert.deepEqual(wrong.map((w) => [path.basename(w.file), w.arch]), [['old.dll', 'x64']]);
});

test('a folder where every program matches reports nothing, and a missing folder is ignored', () => {
    const dir = tempDir();
    fakePe(path.join(dir, 'java.exe'), 0xaa64);
    fakePe(path.join(dir, 'vcruntime140.dll'), 0xaa64);
    assert.deepEqual(peArch.findArchMismatches([dir, path.join(dir, 'nope')], 'arm64'), []);
});

test('the node program running these tests matches this computer', { skip: process.platform !== 'win32' }, () => {
    assert.equal(peArch.peMachine(process.execPath), process.arch === 'arm64' ? 'arm64' : 'x64');
});
