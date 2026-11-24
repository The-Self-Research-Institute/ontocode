'use strict';

const fs = require('fs');
const path = require('path');

const MARKER = '.prepared-for';

function label(platform, arch, version) {
    return `${platform}-${arch}-${version}`;
}

function matches(dir, wanted) {
    try {
        return fs.readFileSync(path.join(dir, MARKER), 'utf8').trim() === wanted;
    } catch {
        return false;
    }
}

function write(dir, wanted) {
    fs.writeFileSync(path.join(dir, MARKER), wanted, 'utf8');
}

module.exports = { MARKER, label, matches, write };
