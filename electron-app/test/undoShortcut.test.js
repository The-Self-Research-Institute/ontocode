'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { undoRedoKind } = require('../services/UndoShortcut');

const press = (key, mods = {}) => ({ type: 'keyDown', key, control: false, meta: false, alt: false, shift: false, ...mods });

test('Ctrl+Z is undo and Cmd+Z is undo', () => {
    assert.equal(undoRedoKind(press('z', { control: true })), 'undo');
    assert.equal(undoRedoKind(press('z', { meta: true })), 'undo');
});

test('Ctrl+Shift+Z and Ctrl+Y are redo, including the capital Z a held shift produces', () => {
    assert.equal(undoRedoKind(press('Z', { control: true, shift: true })), 'redo');
    assert.equal(undoRedoKind(press('z', { meta: true, shift: true })), 'redo');
    assert.equal(undoRedoKind(press('y', { control: true })), 'redo');
});

test('keys without Ctrl or Cmd, with Alt, on key release, or other letters are ignored', () => {
    assert.equal(undoRedoKind(press('z')), null);
    assert.equal(undoRedoKind(press('z', { control: true, alt: true })), null);
    assert.equal(undoRedoKind({ ...press('z', { control: true }), type: 'keyUp' }), null);
    assert.equal(undoRedoKind(press('c', { control: true })), null);
    assert.equal(undoRedoKind(press('y', { control: true, shift: true })), null);
});
