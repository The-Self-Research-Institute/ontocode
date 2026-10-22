'use strict';

function undoRedoKind(input) {
    if (input.type !== 'keyDown' || input.alt || !(input.control || input.meta)) {
        return null;
    }
    const key = String(input.key).toLowerCase();
    if (key === 'z') {
        return input.shift ? 'redo' : 'undo';
    }
    if (key === 'y' && !input.shift) {
        return 'redo';
    }
    return null;
}

module.exports = { undoRedoKind };
