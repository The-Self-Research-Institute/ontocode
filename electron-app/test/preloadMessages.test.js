'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '..', 'preload.js'), 'utf8');

test('every message the preload sends to the page carries the page as its source and origin', () => {
    const constructions = source.match(/new MessageEvent\(/g) || [];
    assert.equal(constructions.length, 1);
    assert.match(source, /new MessageEvent\('message', \{ \.\.\.init, source: window, origin: window\.location\.origin \}\)/);
});

test('no dispatch builds a message event without going through the helper', () => {
    const dispatches = source.match(/window\.dispatchEvent\(\s*pageMessageEvent\(/g) || [];
    assert.ok(dispatches.length >= 10);
    assert.doesNotMatch(source, /dispatchEvent\(\s*new MessageEvent/);
});

test('import status reaches the page as a trusted page message, not a custom event the page drops', () => {
    assert.doesNotMatch(source, /new CustomEvent\('importStatusUpdate'/);
    assert.match(source, /pageMessageEvent\(\{ data: \{ type: 'importStatusUpdate', status \} \}\)/);
});

test('a queue status request is not answered with an invented position', () => {
    assert.doesNotMatch(source, /type: 'queueStatusUpdate'/);
});

test('a project missing from the import queue is not reported as imported on that alone', () => {
    assert.doesNotMatch(source, /status === 'NOT_IN_QUEUE'\)\s*return 'done'/);
    assert.match(source, /if \(status === 'COMPLETED'\) return 'done';/);
});
