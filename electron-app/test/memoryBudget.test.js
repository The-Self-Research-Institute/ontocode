'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const policy = require('../services/ResourcePolicy');

const GB = 1024 ** 3;

test('on an 8 GB machine all children together stay at or below half the RAM', () => {
    const b = policy.computeBudget(8 * GB);
    assert.deepEqual(
        { backend: b.backendMb, fuseki: b.fusekiMb, swrl: b.swrlMb, mongo: b.mongoCacheMb },
        { backend: 1792, fuseki: 1216, swrl: 512, mongo: 512 },
    );
    assert.ok(b.sumMb <= 4096);
});

test('on 16 and 32 GB machines the sum is at most half of the RAM', () => {
    const b16 = policy.computeBudget(16 * GB);
    assert.deepEqual([b16.backendMb, b16.fusekiMb, b16.mongoCacheMb], [3968, 2624, 1024]);
    assert.ok(b16.sumMb <= 8192);
    const b32 = policy.computeBudget(32 * GB);
    assert.deepEqual([b32.backendMb, b32.fusekiMb, b32.mongoCacheMb], [8896, 5888, 1024]);
    assert.ok(b32.sumMb <= 16384);
});

test('very large machines hit the caps and a 4 GB machine still gets the floors', () => {
    const big = policy.computeBudget(256 * GB);
    assert.equal(big.backendMb, 12288);
    assert.equal(big.fusekiMb, 8192);
    const small = policy.computeBudget(4 * GB);
    assert.equal(small.backendMb, 1280);
    assert.equal(small.fusekiMb, 768);
    assert.equal(small.mongoCacheMb, 512);
});

test('the Mongo cache cap is expressed in gigabytes for the mongod flag', () => {
    assert.equal(policy.mongoCacheArg(policy.computeBudget(8 * GB)), '0.5');
    assert.equal(policy.mongoCacheArg(policy.computeBudget(16 * GB)), '1');
});

test('the launcher passes the cache cap to mongod and the SWRL heap comes from the budget', () => {
    const source = fs.readFileSync(path.join(__dirname, '..', 'services', 'ServiceManager.js'), 'utf8');
    assert.ok(source.includes("'--wiredTigerCacheSizeGB', jvmHeaps().mongoCacheGb"));
    assert.ok(source.includes('-Xmx${jvmHeaps().swrlXmx}'));
    assert.ok(!source.includes("'-Xmx512m',\n        ...PLATFORM_JVM_FLAGS"));
});
