'use strict';

const fs = require('fs');
const path = require('path');

function installedModules(jreDir) {
    try {
        const text = fs.readFileSync(path.join(jreDir, 'release'), 'utf8');
        const match = text.match(/^MODULES="([^"]*)"/m);
        return match ? match[1].split(/\s+/).filter(Boolean) : null;
    } catch {
        return null;
    }
}

function missingModules(jreDir, required) {
    const have = installedModules(jreDir);
    if (!have) return [...required];
    return required.filter((name) => !have.includes(name));
}

module.exports = { installedModules, missingModules };
