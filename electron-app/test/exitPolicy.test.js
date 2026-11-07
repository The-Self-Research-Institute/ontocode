'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const policy = require('../services/ResourcePolicy');

test('every JVM gets exit-on-out-of-memory, an error file path and a private temp folder', () => {
    const flags = policy.jvmSafetyFlags({ crashDir: path.join('u', 'diagnostics'), tmpDir: path.join('u', 'tmp') });
    assert.ok(flags.includes('-XX:+ExitOnOutOfMemoryError'));
    assert.ok(flags.some((f) => f.startsWith('-XX:ErrorFile=') && f.includes('hs_err_pid%p.log')));
    assert.ok(flags.includes(`-Djava.io.tmpdir=${path.join('u', 'tmp')}`));
    assert.ok(!flags.some((f) => f.includes('HeapDump')));
});

test('heap dumps are only added when explicitly allowed', () => {
    const flags = policy.jvmSafetyFlags({ crashDir: 'd', tmpDir: 't', heapDump: true });
    assert.ok(flags.includes('-XX:+HeapDumpOnOutOfMemoryError'));
    assert.ok(flags.includes('-XX:HeapDumpPath=d'));
});

test('all three JVM launches use the safety flags', () => {
    const source = fs.readFileSync(path.join(__dirname, '..', 'services', 'ServiceManager.js'), 'utf8');
    assert.equal(source.split('...safetyFlags(),').length - 1, 3);
});

test('an out-of-memory exit is explained in plain words', () => {
    const info = policy.classifyExit({ name: 'Desktop', code: 3, recovery: 'restart' });
    assert.equal(info.outOfMemory, true);
    assert.match(info.message, /ran out of memory/);
    assert.match(info.message, /restarting it now/);
});

test('an out-of-memory line in the output is recognised even with another exit code', () => {
    const info = policy.classifyExit({ name: 'Fuseki', code: 1, outputTail: ['[err] java.lang.OutOfMemoryError: Java heap space'] });
    assert.equal(info.outOfMemory, true);
    assert.match(info.message, /next time it is needed/);
});

test('a clean exit or a requested stop is not reported', () => {
    assert.equal(policy.classifyExit({ name: 'Desktop', code: 0 }), null);
    assert.equal(policy.classifyExit({ name: 'Desktop', code: 1, stopping: true }), null);
});

test('the backend restart budget allows two tries and then refuses until the window passes', () => {
    let now = 0;
    const b = new policy.RestartBudget({ max: 2, windowMs: 1000, now: () => now });
    assert.equal(b.tryTake(), true);
    assert.equal(b.tryTake(), true);
    assert.equal(b.tryTake(), false);
    now = 1500;
    assert.equal(b.tryTake(), true);
});
