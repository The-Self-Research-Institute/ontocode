import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const FORBIDDEN = [
    /^\s*import\s+(static\s+)?org\.springframework\.jdbc\./,
    /^\s*import\s+(static\s+)?java\.sql\./,
    /^\s*import\s+(static\s+)?javax\.sql\./,
    /^\s*import\s+(static\s+)?org\.postgresql\./
];
const ALLOWED_PATH = /\/(repository|persistence)\/|\/config\//;
const SKIP = new Set(['node_modules', 'target', '.git', '.claude', 'dist', 'out']);

function modulesUsingCommonDb() {
    const found = [];
    for (const entry of fs.readdirSync(ROOT, { withFileTypes: true })) {
        if (!entry.isDirectory() || SKIP.has(entry.name)) continue;
        const pom = path.join(ROOT, entry.name, 'pom.xml');
        if (fs.existsSync(pom) && /<artifactId>common-db<\/artifactId>/.test(fs.readFileSync(pom, 'utf8'))) {
            found.push(entry.name);
        }
    }
    return found;
}

function walk(dir, out) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        if (SKIP.has(entry.name)) continue;
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) walk(full, out);
        else if (entry.name.endsWith('.java')) out.push(full);
    }
}

const modules = modulesUsingCommonDb();
const offenders = [];
let checked = 0;
for (const mod of modules) {
    const files = [];
    const main = path.join(ROOT, mod, 'src', 'main', 'java');
    if (fs.existsSync(main)) walk(main, files);
    for (const file of files) {
        const rel = path.relative(ROOT, file).split(path.sep).join('/');
        checked++;
        if (ALLOWED_PATH.test('/' + rel)) continue;
        const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
        lines.forEach((line, i) => {
            if (FORBIDDEN.some(re => re.test(line))) offenders.push(rel + ':' + (i + 1) + ': ' + line.trim());
        });
    }
}

console.log('Modules on the database layer: ' + (modules.join(', ') || 'none'));
console.log('Files checked: ' + checked);
if (offenders.length) {
    console.log('SQL or JDBC types used outside a repository, persistence or config package:');
    offenders.forEach(o => console.log('  ' + o));
    process.exit(1);
}
console.log('OK: SQL and JDBC types stay inside the repository packages.');
