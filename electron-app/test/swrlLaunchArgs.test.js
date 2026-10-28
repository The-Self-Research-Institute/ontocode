'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('the desktop SWRL launch turns off the project access check because the desktop client sends no login token', () => {
    const source = fs.readFileSync(path.join(__dirname, '..', 'services', 'ServiceManager.js'), 'utf8');
    const start = source.indexOf('async function startSwrl()');
    const end = source.indexOf('function hasExited', start);
    assert.ok(start > 0 && end > start);
    assert.ok(source.slice(start, end).includes("'--swrl.access-check.enabled=false'"));
});
