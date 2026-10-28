'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const hygiene = require('../services/LogHygiene');

function tmp() {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'loghyg-'));
}

test('a log over the cap is shifted into numbered files and only three are kept', () => {
    const dir = tmp();
    const f = path.join(dir, 'fuseki.log');
    for (let round = 1; round <= 5; round++) {
        fs.writeFileSync(f, `round${round}`.padEnd(100, 'x'));
        assert.equal(hygiene.rotateIfLarge(f, 50, 3), true);
    }
    assert.equal(fs.existsSync(f), false);
    assert.ok(fs.readFileSync(`${f}.1`, 'utf8').startsWith('round5'));
    assert.ok(fs.readFileSync(`${f}.3`, 'utf8').startsWith('round3'));
    assert.equal(fs.existsSync(`${f}.4`), false);
});

test('a small log is left alone', () => {
    const dir = tmp();
    const f = path.join(dir, 'a.log');
    fs.writeFileSync(f, 'tiny');
    assert.equal(hygiene.rotateIfLarge(f, 50, 3), false);
    assert.equal(fs.readFileSync(f, 'utf8'), 'tiny');
});

test('startup rotation covers every .log file in the folder plus the extra files', () => {
    const dir = tmp();
    fs.writeFileSync(path.join(dir, 'mongo.log'), 'x'.repeat(hygiene.MAX_LOG_BYTES + 1));
    fs.writeFileSync(path.join(dir, 'desktop.log'), 'small');
    const crash = path.join(dir, 'crash.txt');
    fs.writeFileSync(crash, 'x'.repeat(100));
    const rotated = hygiene.rotateLogsAtStartup(dir, [{ file: crash, maxBytes: 50 }]);
    assert.deepEqual(rotated.sort(), ['crash.txt', 'mongo.log']);
    assert.ok(fs.existsSync(path.join(dir, 'mongo.log.1')));
    assert.equal(fs.readFileSync(path.join(dir, 'desktop.log'), 'utf8'), 'small');
});

test('the rotating stream rolls to a new file once the cap is reached', async () => {
    const dir = tmp();
    const f = path.join(dir, 'swrl.log');
    const s = hygiene.createRotatingStream(f, { maxBytes: 100, keep: 3 });
    s.write(Buffer.alloc(120, 'a'));
    await new Promise((r) => setTimeout(r, 150));
    s.write(Buffer.from('after'));
    s.end();
    await new Promise((r) => setTimeout(r, 150));
    assert.equal(fs.readFileSync(`${f}.1`).length, 120);
    assert.equal(fs.readFileSync(f, 'utf8'), 'after');
});

test('the crash log is capped when appending', () => {
    const dir = tmp();
    const f = path.join(dir, 'crash.log');
    fs.writeFileSync(f, 'x'.repeat(200));
    hygiene.appendCapped(f, 'new\n', 100);
    assert.equal(fs.readFileSync(f, 'utf8'), 'new\n');
    assert.equal(fs.readFileSync(`${f}.1`).length, 200);
});

test('the private temp folder is emptied at start and old diagnostics are pruned to the newest few', () => {
    const dir = tmp();
    const t = path.join(dir, 'tmp');
    fs.mkdirSync(t);
    fs.writeFileSync(path.join(t, 'old.bin'), 'x');
    hygiene.resetDir(t);
    assert.deepEqual(fs.readdirSync(t), []);

    const d = path.join(dir, 'diag');
    fs.mkdirSync(d);
    for (let i = 0; i < 5; i++) {
        const p = path.join(d, `f${i}`);
        fs.writeFileSync(p, 'x');
        fs.utimesSync(p, new Date(1000 + i * 1000), new Date(1000 + i * 1000));
    }
    assert.equal(hygiene.pruneDir(d, 3), 2);
    assert.deepEqual(fs.readdirSync(d).sort(), ['f2', 'f3', 'f4']);
});

test('the launcher uses the rotating writer for service logs and Syncthing', () => {
    const sm = fs.readFileSync(path.join(__dirname, '..', 'services', 'ServiceManager.js'), 'utf8');
    const sync = fs.readFileSync(path.join(__dirname, '..', 'services', 'SyncManager.js'), 'utf8');
    assert.ok(sm.includes('createRotatingStream(logFile)'));
    assert.ok(sync.includes('createRotatingStream(logFile)'));
    assert.ok(sm.includes("'--logRotate', 'rename'"));
});
