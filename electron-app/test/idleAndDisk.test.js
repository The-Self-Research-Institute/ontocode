'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const policy = require('../services/ResourcePolicy');

test('a tracked service is due only after the idle period with no activity', () => {
    let now = 0;
    const t = new policy.IdleTracker({ idleMs: 1000, now: () => now });
    t.track('Fuseki');
    t.track('SWRL');
    now = 900;
    assert.deepEqual(t.due(), []);
    t.touch('Fuseki');
    now = 1100;
    assert.deepEqual(t.due(), ['SWRL']);
    now = 2000;
    assert.deepEqual(t.due().sort(), ['Fuseki', 'SWRL']);
});

test('nothing is stopped while a request is in flight', () => {
    let now = 0;
    const t = new policy.IdleTracker({ idleMs: 1000, now: () => now });
    t.track('Fuseki');
    t.begin();
    now = 5000;
    assert.deepEqual(t.due(), []);
    t.end();
    assert.deepEqual(t.due(), ['Fuseki']);
});

test('touching or untracking a service that is not running does nothing', () => {
    let now = 0;
    const t = new policy.IdleTracker({ idleMs: 1000, now: () => now });
    t.touch('SWRL');
    now = 5000;
    assert.deepEqual(t.due(), []);
    t.track('SWRL');
    t.untrack('SWRL');
    assert.deepEqual(t.due(), []);
});

test('the idle period defaults to 30 minutes, can be changed, and 0 turns it off', () => {
    assert.equal(policy.idleMsFromEnv({}), 30 * 60 * 1000);
    assert.equal(policy.idleMsFromEnv({ ONTOCODE_IDLE_STOP_MINUTES: '5' }), 5 * 60 * 1000);
    assert.equal(policy.idleMsFromEnv({ ONTOCODE_IDLE_STOP_MINUTES: 'abc' }), 30 * 60 * 1000);
    const off = new policy.IdleTracker({ idleMs: policy.idleMsFromEnv({ ONTOCODE_IDLE_STOP_MINUTES: '0' }), now: () => 1e12 });
    off.track('Fuseki');
    assert.deepEqual(off.due(), []);
});

const GB = 1024 ** 3;
const fakeFs = (freeBytes) => ({ statfsSync: () => ({ bavail: freeBytes / 4096, bsize: 4096 }) });

test('free space of 2 GB or more is fine', () => {
    assert.equal(policy.diskStatus('x', fakeFs(5 * GB)).level, 'ok');
    assert.equal(policy.diskMessage(policy.diskStatus('x', fakeFs(5 * GB))), '');
});

test('under 2 GB the user gets a plain low-space message', () => {
    const s = policy.diskStatus('x', fakeFs(1 * GB));
    assert.equal(s.level, 'low');
    assert.match(policy.diskMessage(s), /running low on disk space \(1024 MB free\)/);
});

test('under 256 MB startup is refused with a plain message', () => {
    const s = policy.diskStatus('x', fakeFs(100 * 1024 * 1024));
    assert.equal(s.level, 'critical');
    assert.match(policy.diskMessage(s), /cannot start safely/);
});

test('when free space cannot be read the check stays quiet', () => {
    const s = policy.diskStatus('x', { statfsSync: () => { throw new Error('no'); } });
    assert.equal(s.level, 'unknown');
    assert.equal(policy.diskMessage(s), '');
});
