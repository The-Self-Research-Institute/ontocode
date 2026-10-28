'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { EventEmitter } = require('node:events');
const { createProcessGuard } = require('../services/ProcessGuard');

function setup(platform, responses = {}) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'guard-'));
    const calls = [];
    const run = (cmd, args) => {
        calls.push([cmd, ...args]);
        const key = [cmd, ...args].join(' ');
        for (const [pattern, value] of Object.entries(responses)) {
            if (key.includes(pattern)) return { status: 0, stdout: value };
        }
        return { status: 0, stdout: '' };
    };
    const kills = [];
    const guard = createProcessGuard({
        registryFile: path.join(dir, 'children.json'),
        marker: 'OntoCodeUserData',
        platform,
        run,
        kill: (pid, sig) => kills.push([pid, sig]),
    });
    return { dir, calls, kills, guard };
}

function fakeChild(pid) {
    const c = new EventEmitter();
    c.pid = pid;
    return c;
}

function seed(dir, entries) {
    fs.writeFileSync(path.join(dir, 'children.json'), JSON.stringify({ entries }));
}

test('a spawned child is written to the pid file and removed when it exits', () => {
    const { guard } = setup('win32');
    const c = fakeChild(111);
    guard.register('MongoDB', 'mongod.exe', c);
    assert.deepEqual(guard.readRegistry().map((e) => [e.pid, e.exe]), [[111, 'mongod.exe']]);
    c.emit('exit', 0);
    assert.deepEqual(guard.readRegistry(), []);
});

test('on Windows every live child is stopped with a process tree kill of its own pid', () => {
    const { guard, calls } = setup('win32');
    guard.register('Desktop', 'java.exe', fakeChild(222));
    guard.register('MongoDB', 'mongod.exe', fakeChild(333));
    guard.killAllSync();
    assert.deepEqual(calls.filter((c) => c[0] === 'taskkill').map((c) => c.slice(1).join(' ')), [
        '/PID 222 /T /F',
        '/PID 333 /T /F',
    ]);
    assert.deepEqual(guard.readRegistry(), []);
});

test('on other systems a live child is killed by pid', () => {
    const { guard, kills } = setup('linux');
    guard.register('Desktop', 'java', fakeChild(444));
    guard.killAllSync();
    assert.deepEqual(kills, [[444, 'SIGKILL']]);
});

test('at startup a leftover child is killed only when the program name and the command line both match', () => {
    const { dir, guard, calls } = setup('win32', {
        'tasklist /FI PID eq 555': '"mongod.exe","555","Console","1","50,000 K"',
        'ProcessId=555': 'mongod.exe --dbpath C:/OntoCodeUserData/data',
    });
    seed(dir, [{ pid: 555, name: 'MongoDB', exe: 'mongod.exe' }]);
    const result = guard.reapStale();
    assert.deepEqual(result.reaped, [{ pid: 555, name: 'MongoDB' }]);
    assert.ok(calls.some((c) => c[0] === 'taskkill' && c.includes('555')));
    assert.deepEqual(guard.readRegistry(), []);
});

test('a recorded pid that is now a different program is never killed', () => {
    const { dir, guard, calls } = setup('win32', {
        'tasklist /FI PID eq 666': '"chrome.exe","666","Console","1","50,000 K"',
    });
    seed(dir, [{ pid: 666, name: 'MongoDB', exe: 'mongod.exe' }]);
    const result = guard.reapStale();
    assert.deepEqual(result.reaped, []);
    assert.equal(result.skipped[0].reason, 'different program');
    assert.ok(!calls.some((c) => c[0] === 'taskkill'));
});

test('the same program started by someone else is never killed', () => {
    const { dir, guard, calls } = setup('win32', {
        'tasklist /FI PID eq 777': '"java.exe","777","Console","1","50,000 K"',
        'ProcessId=777': 'java.exe -jar C:\\other\\app.jar',
    });
    seed(dir, [{ pid: 777, name: 'Desktop', exe: 'java.exe' }]);
    const result = guard.reapStale();
    assert.deepEqual(result.reaped, []);
    assert.equal(result.skipped[0].reason, 'not started by OntoCode');
    assert.ok(!calls.some((c) => c[0] === 'taskkill'));
});

test('a recorded pid that no longer exists is simply forgotten', () => {
    const { dir, guard, calls } = setup('win32', {
        tasklist: 'INFO: No tasks are running which match the specified criteria.',
    });
    seed(dir, [{ pid: 888, name: 'Desktop', exe: 'java.exe' }]);
    assert.deepEqual(guard.reapStale().reaped, []);
    assert.ok(!calls.some((c) => c[0] === 'taskkill'));
    assert.deepEqual(guard.readRegistry(), []);
});

test('cleaning up leftovers keeps the entries of children already started in this run', () => {
    const { guard } = setup('linux');
    guard.register('Syncthing', 'syncthing', fakeChild(999));
    guard.reapStale();
    assert.deepEqual(guard.readRegistry().map((e) => e.pid), [999]);
});

test('the launcher never lists all processes and never kills by name', () => {
    const source = fs.readFileSync(path.join(__dirname, '..', 'services', 'ProcessGuard.js'), 'utf8');
    assert.ok(!/\/IM\b|pkill|killall|wmic process|Get-Process/.test(source));
});
