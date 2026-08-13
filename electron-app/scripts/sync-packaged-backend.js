
const fs = require('fs');
const path = require('path');

const ELECTRON_APP = path.resolve(__dirname, '..');
const SRC = path.join(ELECTRON_APP, 'resources', 'backend');
const DEFAULT_TARGET = path.join(ELECTRON_APP, 'dist-electron', 'win-unpacked', 'resources', 'backend');

function parseTarget(argv) {
    for (const arg of argv) {
        if (arg.startsWith('--target=')) return arg.slice('--target='.length);
    }
    return DEFAULT_TARGET;
}

function copyDir(src, dest) {
    if (!fs.existsSync(src)) {
        throw new Error(`Missing source: ${src}`);
    }
    fs.mkdirSync(dest, { recursive: true });
    for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
        const from = path.join(src, entry.name);
        const to = path.join(dest, entry.name);
        if (entry.isDirectory()) {
            copyDir(from, to);
        } else {
            fs.mkdirSync(path.dirname(to), { recursive: true });
            fs.copyFileSync(from, to);
        }
    }
}

function main() {
    const target = parseTarget(process.argv.slice(2));
    const required = [
        path.join(SRC, 'jars', 'desktop.jar'),
        path.join(SRC, 'jars', 'fuseki-server.jar'),
        path.join(SRC, 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java'),
    ];
    const missing = required.filter((p) => !fs.existsSync(p));
    if (missing.length) {
        console.error('Source backend is incomplete. Run first:\n  node scripts/build-desktop.js --java --resources\n');
        missing.forEach((p) => console.error('  missing:', p));
        process.exit(1);
    }

    console.log(`Syncing backend → ${target}`);
    for (const sub of ['jars', 'jre', 'mongodb', 'plugin-bundles', 'fuseki']) {
        const srcSub = path.join(SRC, sub);
        if (!fs.existsSync(srcSub)) continue;
        copyDir(srcSub, path.join(target, sub));
        console.log(`  ✓  ${sub}/`);
    }
    console.log('Done. Restart OntoCode Desktop.');
}

main();
