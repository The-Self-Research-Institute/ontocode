'use strict';

const fs = require('fs');
const path = require('path');

const MAX_LOG_BYTES = 20 * 1024 * 1024;
const KEEP_FILES = 3;
const MAX_CRASH_LOG_BYTES = 2 * 1024 * 1024;

function rotateIfLarge(file, maxBytes = MAX_LOG_BYTES, keep = KEEP_FILES, fsImpl = fs) {
    let size;
    try {
        size = fsImpl.statSync(file).size;
    } catch (_) {
        return false;
    }
    if (size < maxBytes) return false;
    try {
        fsImpl.rmSync(`${file}.${keep}`, { force: true });
        for (let i = keep - 1; i >= 1; i--) {
            if (fsImpl.existsSync(`${file}.${i}`)) fsImpl.renameSync(`${file}.${i}`, `${file}.${i + 1}`);
        }
        fsImpl.renameSync(file, `${file}.1`);
        return true;
    } catch (_) {
        try {
            fsImpl.truncateSync(file, 0);
            return true;
        } catch (__) {
            return false;
        }
    }
}

function rotateLogsAtStartup(logsDir, extraFiles = [], fsImpl = fs) {
    const rotated = [];
    let names = [];
    try {
        names = fsImpl.readdirSync(logsDir).filter((n) => /\.log$/i.test(n));
    } catch (_) {}
    for (const n of names) {
        if (rotateIfLarge(path.join(logsDir, n), MAX_LOG_BYTES, KEEP_FILES, fsImpl)) rotated.push(n);
    }
    for (const f of extraFiles) {
        if (rotateIfLarge(f.file, f.maxBytes || MAX_LOG_BYTES, KEEP_FILES, fsImpl)) rotated.push(path.basename(f.file));
    }
    return rotated;
}

function createRotatingStream(file, { maxBytes = MAX_LOG_BYTES, keep = KEEP_FILES, fsImpl = fs } = {}) {
    let stream = null;
    let written = 0;
    let ended = false;

    function open() {
        try {
            written = fsImpl.existsSync(file) ? fsImpl.statSync(file).size : 0;
        } catch (_) {
            written = 0;
        }
        stream = fsImpl.createWriteStream(file, { flags: 'a' });
        stream.on('error', () => {});
    }

    function roll() {
        const old = stream;
        stream = null;
        const finish = () => {
            if (ended) return;
            rotateIfLarge(file, 1, keep, fsImpl);
            open();
        };
        if (old) old.end(finish); else finish();
    }

    rotateIfLarge(file, maxBytes, keep, fsImpl);
    open();

    return {
        write(chunk) {
            if (ended || !stream) return;
            written += chunk.length;
            stream.write(chunk);
            if (written >= maxBytes) roll();
        },
        end() {
            ended = true;
            if (stream) stream.end();
        },
    };
}

function appendCapped(file, text, maxBytes = MAX_CRASH_LOG_BYTES, fsImpl = fs) {
    rotateIfLarge(file, maxBytes, KEEP_FILES, fsImpl);
    fsImpl.appendFileSync(file, text);
}

function resetDir(dir, fsImpl = fs) {
    try {
        fsImpl.rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
    } catch (_) {}
    fsImpl.mkdirSync(dir, { recursive: true });
}

function pruneDir(dir, maxFiles, fsImpl = fs) {
    let entries = [];
    try {
        entries = fsImpl.readdirSync(dir).map((n) => {
            const p = path.join(dir, n);
            return { p, t: fsImpl.statSync(p).mtimeMs };
        });
    } catch (_) {
        return 0;
    }
    entries.sort((a, b) => b.t - a.t);
    let removed = 0;
    for (const e of entries.slice(maxFiles)) {
        try { fsImpl.rmSync(e.p, { recursive: true, force: true }); removed++; } catch (_) {}
    }
    return removed;
}

module.exports = {
    MAX_LOG_BYTES, KEEP_FILES, MAX_CRASH_LOG_BYTES,
    rotateIfLarge, rotateLogsAtStartup, createRotatingStream, appendCapped, resetDir, pruneDir,
};
