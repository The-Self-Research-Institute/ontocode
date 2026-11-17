'use strict';

const fs = require('fs');
const path = require('path');

const MACHINES = { 0x14c: 'x86', 0x8664: 'x64', 0xaa64: 'arm64' };

function peMachine(file) {
    let fd;
    try {
        fd = fs.openSync(file, 'r');
        const head = Buffer.alloc(64);
        if (fs.readSync(fd, head, 0, 64, 0) < 64 || head.readUInt16LE(0) !== 0x5a4d) return null;
        const sig = Buffer.alloc(6);
        if (fs.readSync(fd, sig, 0, 6, head.readUInt32LE(0x3c)) < 6 || sig.readUInt32LE(0) !== 0x00004550) return null;
        const machine = sig.readUInt16LE(4);
        return MACHINES[machine] || `unknown(0x${machine.toString(16)})`;
    } catch (_) {
        return null;
    } finally {
        if (fd !== undefined) {
            try { fs.closeSync(fd); } catch (_) {}
        }
    }
}

function listNativeFiles(dir) {
    if (!fs.existsSync(dir)) return [];
    const found = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) found.push(...listNativeFiles(full));
        else if (/\.(exe|dll)$/i.test(entry.name)) found.push(full);
    }
    return found;
}

function findArchMismatches(dirs, expected) {
    const wrong = [];
    for (const dir of dirs) {
        for (const file of listNativeFiles(dir)) {
            const arch = peMachine(file);
            if (arch && arch !== expected) wrong.push({ file, arch });
        }
    }
    return wrong;
}

module.exports = { peMachine, listNativeFiles, findArchMismatches };
