import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const args = new Set(process.argv.slice(2));
const CHECK = args.has('--check');
const OUT_MD = path.join(ROOT, 'docs', 'postgres-migration-ledger.md');
const OUT_JSON = path.join(ROOT, 'docs', 'postgres-migration-ledger.json');

const SKIP_DIRS = new Set(['.git', '.claude', 'node_modules', 'target', 'dist', 'out', 'build', '.flatpak-builder', '.idea', '.vscode', '.mvn', 'coverage', 'win-unpacked']);
const SKIP_FILES = new Set(['package-lock.json', 'yarn.lock', 'pnpm-lock.yaml', 'postgres-migration-ledger.md', 'postgres-migration-ledger.json']);
const SKIP_PATH_PARTS = ['scripts/migration/', 'shared/common-db/', 'electron-app/resources/', 'ontology-vscode-extension/out/', 'ontology-vscode-extension/dist/', 'flathub/generated/'];
const TEXT_EXT = new Set(['.java', '.xml', '.yml', '.yaml', '.properties', '.js', '.mjs', '.cjs', '.ts', '.tsx', '.sh', '.bat', '.ps1', '.cs', '.md', '.html', '.json', '.toml', '.cff', '.txt', '.sql', '.conf', '.env', '.example', '.ttl']);

const BANNED = [
    'org.springframework.data.mongodb', 'com.mongodb', 'org.bson', 'io.mongock', '@EnableMongock', 'mongock',
    'MongoTemplate', 'MongoRepository', 'MongoOperations', 'DataMongoTest', 'GridFsTemplate', 'GridFSBucket', 'GridFs', 'gridfs', 'ObjectId',
    'spring-boot-starter-data-mongodb', 'mongodb-springdata', 'mongodb://', 'spring.data.mongodb', 'SPRING_DATA_MONGODB', 'MONGODB_', 'MONGO_',
    '27017', '27018', '27027', '27117', '27300', 'mongod', 'mongosh', 'mongodump', 'mongorestore', 'wiredTiger', 'image: mongo', 'mongo-data',
    'fastdl.mongodb.org', 'MongoDB', 'SSPL', 'mongo'
];

function walk(dir, files) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        if (entry.isDirectory()) {
            if (SKIP_DIRS.has(entry.name)) continue;
            walk(path.join(dir, entry.name), files);
        } else if (entry.isFile()) {
            if (SKIP_FILES.has(entry.name)) continue;
            const full = path.join(dir, entry.name);
            const rel = path.relative(ROOT, full).split(path.sep).join('/');
            if (SKIP_PATH_PARTS.some(p => rel.includes(p))) continue;
            const ext = path.extname(entry.name).toLowerCase();
            if (!TEXT_EXT.has(ext) && !/^Dockerfile/.test(entry.name) && !/^\.env/.test(entry.name)) continue;
            files.push({ full, rel, ext });
        }
    }
}

function moduleOf(rel) {
    const first = rel.split('/')[0];
    if (rel.startsWith('shared/')) return rel.split('/').slice(0, 2).join('/');
    return first;
}

function readText(full) {
    try {
        const buf = fs.readFileSync(full);
        if (buf.length > 4 * 1024 * 1024) return null;
        return buf.toString('utf8');
    } catch {
        return null;
    }
}

function lineOf(text, index) {
    let n = 1;
    for (let i = 0; i < index; i++) if (text.charCodeAt(i) === 10) n++;
    return n;
}

function decap(name) {
    return name.charAt(0).toLowerCase() + name.slice(1);
}

const all = [];
walk(ROOT, all);

const documents = [];
const repositories = [];
const templateFiles = [];
const gridFsFiles = [];
const objectIdFiles = [];
const rawDriverFiles = [];
const testFiles = [];
const configFiles = [];
const bannedByFile = new Map();
const ledgerNotes = [];

const MONGO_JAVA = /org\.springframework\.data\.mongodb|com\.mongodb|org\.bson|GridFs|GridFS|MongoRepository|MongoTemplate|MongoOperations|io\.mongock|@Document\b/;
const DISTINCT_OPS = ['findAndModify', 'findAndReplace', 'findAndRemove', 'upsert', 'updateFirst', 'updateMulti', 'findDistinct', 'bulkOps', 'indexOps', 'getCollection', 'getDb', 'aggregate', 'dropCollection', 'createCollection', 'collectionExists', 'getConverter', 'ensureIndex'];
const GENERIC_OPS = ['find', 'findOne', 'remove', 'insert', 'save', 'count', 'exists', 'stream'];
const GRID_OPS = ['store', 'find', 'findOne', 'delete', 'getResource', 'getResources'];
const KNOWN_PLAIN = new Set(['users', 'projects', 'workspaces', 'plugins', 'invitations', 'ontologies']);

for (const file of all) {
    const text = readText(file.full);
    if (text === null) continue;
    const mod = moduleOf(file.rel);
    const isJava = file.ext === '.java';
    const isTest = file.rel.includes('/src/test/');

    if (isJava) {
        if (!MONGO_JAVA.test(text)) continue;

        if (isTest) {
            const kinds = [];
            for (const k of ['MongoTemplate', 'MongoRepository', 'MongoOperations', 'org.bson', 'com.mongodb', 'GridFs', 'springframework.data.mongodb']) {
                if (text.includes(k)) kinds.push(k);
            }
            testFiles.push({ file: file.rel, module: mod, kinds });
            continue;
        }

        const docRe = /@Document\b/g;
        let m;
        while ((m = docRe.exec(text)) !== null) {
            let argText = '';
            let cursor = m.index + m[0].length;
            while (/\s/.test(text[cursor] || '')) cursor++;
            if (text[cursor] === '(') {
                let depth = 0;
                let end = cursor;
                for (; end < text.length; end++) {
                    if (text[end] === '(') depth++;
                    else if (text[end] === ')') {
                        depth--;
                        if (depth === 0) break;
                    }
                }
                argText = text.slice(cursor + 1, end);
            }
            const classMatch = /\bclass\s+(\w+)/.exec(text.slice(m.index, m.index + 4000));
            if (!classMatch) continue;
            m = Object.assign([m[0], null, argText, classMatch[1]], { index: m.index });
            let collection = null;
            const named = /(?:collection|value)\s*=\s*"([^"]+)"/.exec(argText);
            const bare = /^\s*"([^"]+)"\s*$/.exec(argText);
            if (named) collection = named[1];
            else if (bare) collection = bare[1];
            else collection = decap(m[3]);
            const idMatch = /@Id\s+(?:private\s+)?([\w<>]+)\s+(\w+)/.exec(text);
            documents.push({
                class: m[3],
                collection,
                module: mod,
                file: file.rel,
                idType: idMatch ? idMatch[1] : null,
                indexed: (text.match(/@Indexed/g) || []).length,
                compoundIndexes: (text.match(/@CompoundIndex\b/g) || []).length,
                ttl: /expireAfter/.test(text),
                unique: /unique\s*=\s*true/.test(text),
                sparse: /sparse\s*=\s*true/.test(text),
                nestedMaps: (text.match(/Map<\s*String\s*,\s*Object\s*>/g) || []).length,
                dateTypes: ['Date', 'LocalDateTime', 'Instant', 'ZonedDateTime', 'OffsetDateTime'].filter(t => new RegExp('\\b' + t + '\\b').test(text))
            });
        }

        const repoRe = /interface\s+(\w+)\s+extends\s+(?:\w+,\s*)*(?:Mongo|Reactive|Crud|PagingAndSorting)\w*Repository\s*<\s*([\w.]+)/g;
        while ((m = repoRe.exec(text)) !== null) {
            const methods = (text.match(/^\s*(?:@\w+(?:\([^)]*\))?\s*)*[\w<>\[\],.? ]+\s+\w+\s*\([^)]*\)\s*;/gm) || []).length;
            repositories.push({
                interface: m[1],
                entity: m[2],
                module: mod,
                file: file.rel,
                methods,
                queryAnnotations: (text.match(/@Query\s*\(/g) || []).length,
                deleteBy: (text.match(/\bdeleteBy\w+/g) || []).length,
                ignoreCase: (text.match(/IgnoreCase/g) || []).length,
                nestedPath: (text.match(/\w+_\w+\s*\(/g) || []).length
            });
        }

        const usesTemplate = /MongoTemplate|MongoOperations/.test(text);
        if (usesTemplate) {
            const ops = {};
            const lines = [];
            for (const op of DISTINCT_OPS) {
                const re = new RegExp('\\.' + op + '\\(', 'g');
                let n;
                while ((n = re.exec(text)) !== null) {
                    ops[op] = (ops[op] || 0) + 1;
                    lines.push(op + ':' + lineOf(text, n.index));
                }
            }
            for (const op of GENERIC_OPS) {
                const count = (text.match(new RegExp('\\.' + op + '\\(', 'g')) || []).length;
                if (count) ops[op] = count;
            }
            const collections = new Set();
            const nameRe = /\.(?:find|findOne|remove|insert|save|count|exists|upsert|updateFirst|updateMulti|findAndModify|indexOps|getCollection|aggregate|bulkOps)\([^;]*?"([a-z][a-z0-9_]+)"/g;
            while ((m = nameRe.exec(text)) !== null) {
                if (m[1].includes('_') || KNOWN_PLAIN.has(m[1])) collections.add(m[1]);
            }
            const constRe = /\b\w*COLLECTION\w*\s*=\s*"([a-z][a-z0-9_]+)"/g;
            while ((m = constRe.exec(text)) !== null) collections.add(m[1]);
            templateFiles.push({
                file: file.rel,
                module: mod,
                ops,
                lines: lines.slice(0, 40),
                stringCollections: [...collections].sort(),
                criteria: (text.match(/Criteria\./g) || []).length,
                hotAtomic: Boolean(ops.findAndModify || ops.findAndReplace || ops.upsert)
            });
        }

        if (/GridFs|GridFS/.test(text)) {
            const ops = {};
            for (const op of GRID_OPS) {
                const count = (text.match(new RegExp('\\.' + op + '\\(', 'g')) || []).length;
                if (count) ops[op] = count;
            }
            gridFsFiles.push({
                file: file.rel,
                module: mod,
                ops,
                buffers: /readAllBytes|toByteArray|getBytes\(|ByteArrayOutputStream|ByteArrayInputStream/.test(text),
                metadataKeys: [...new Set([...text.matchAll(/metadata\.(\w+)/g)].map(x => x[1]))].sort()
            });
        }

        if (/ObjectId/.test(text)) {
            objectIdFiles.push({
                file: file.rel,
                module: mod,
                newObjectId: (text.match(/new\s+ObjectId\s*\(/g) || []).length,
                isValid: (text.match(/ObjectId\.isValid/g) || []).length,
                toHex: (text.match(/toHexString|getObjectId\(\)/g) || []).length
            });
        }

        if (/org\.bson|com\.mongodb/.test(text)) {
            rawDriverFiles.push({
                file: file.rel,
                module: mod,
                imports: [...new Set([...text.matchAll(/import\s+(?:static\s+)?((?:org\.bson|com\.mongodb)[\w.]*)/g)].map(x => x[1]))].sort()
            });
        }

        if (/class\s+\w+/.test(text) && /(MongoConfig|MongoTimeoutConfig|MongockConfig|MongoMapKeyConfig|IndexInitializer|@EnableMongock|@ChangeUnit|@EnableMongoRepositories|MongoClientSettings|MongoCustomConversions)/.test(text)) {
            configFiles.push({ file: file.rel, module: mod });
        }
    }

    const lowered = text.toLowerCase();
    if (lowered.includes('mongo') || /gridfs|objectid|sspl|27017|27018|27027|27117|27300|wiredtiger/i.test(text)) {
        const hits = {};
        for (const word of BANNED) {
            const needle = word.toLowerCase();
            let idx = lowered.indexOf(needle);
            let count = 0;
            while (idx !== -1) {
                count++;
                idx = lowered.indexOf(needle, idx + needle.length);
            }
            if (count) hits[word] = count;
        }
        const sampleLines = [];
        const lines = text.split(/\r?\n/);
        for (let i = 0; i < lines.length && sampleLines.length < 3; i++) {
            if (/mongo|gridfs|objectid|sspl|27017|27018|27027|27117|27300|wiredtiger/i.test(lines[i])) {
                sampleLines.push((i + 1) + ': ' + lines[i].trim().slice(0, 140));
            }
        }
        bannedByFile.set(file.rel, { file: file.rel, module: mod, test: isTest, hits, total: Object.values(hits).reduce((a, b) => a + b, 0), sampleLines });
    }
}

const byModule = (items, key = 'module') => {
    const out = {};
    for (const it of items) out[it[key]] = (out[it[key]] || 0) + 1;
    return out;
};

const collectionsFromDocs = new Map();
for (const d of documents) {
    const list = collectionsFromDocs.get(d.collection) || [];
    list.push(d);
    collectionsFromDocs.set(d.collection, list);
}
const knownCollections = new Set(collectionsFromDocs.keys());
const rawOnlyCollections = new Map();
for (const t of templateFiles) {
    for (const name of t.stringCollections) {
        if (!knownCollections.has(name)) {
            const list = rawOnlyCollections.get(name) || [];
            list.push(t.file);
            rawOnlyCollections.set(name, list);
        }
    }
}

const nonJava = [...bannedByFile.values()].filter(f => !f.file.endsWith('.java') && !f.test);
const javaTestRefs = testFiles.length;

const summary = {
    generatedAt: new Date().toISOString(),
    documents: documents.length,
    distinctCollectionsFromDocuments: knownCollections.size,
    sharedCollections: [...collectionsFromDocs.entries()].filter(([, v]) => v.length > 1).map(([k, v]) => ({ collection: k, classes: v.map(x => x.module + ':' + x.class) })),
    repositories: repositories.length,
    templateFiles: templateFiles.length,
    gridFsFiles: gridFsFiles.length,
    objectIdFiles: objectIdFiles.length,
    rawDriverFiles: rawDriverFiles.length,
    configClasses: configFiles.length,
    testFilesTouchingMongoTypes: javaTestRefs,
    rawOnlyCollections: [...rawOnlyCollections.entries()].map(([k, v]) => ({ collection: k, files: v })),
    nonJavaFilesMentioningMongo: nonJava.length,
    bannedTotalOccurrences: [...bannedByFile.values()].filter(f => !f.test).reduce((a, f) => a + Object.entries(f.hits).filter(([w]) => w !== 'mongo' && w !== 'MongoDB').reduce((s, [, c]) => s + c, 0), 0),
    byModule: {
        documents: byModule(documents),
        repositories: byModule(repositories),
        templateFiles: byModule(templateFiles),
        gridFsFiles: byModule(gridFsFiles),
        tests: byModule(testFiles)
    }
};

if (CHECK) {
    const allow = path.join(ROOT, 'scripts', 'migration', 'mongo-allowlist.txt');
    const allowed = fs.existsSync(allow) ? fs.readFileSync(allow, 'utf8').split(/\r?\n/).filter(l => l && !l.startsWith('#')) : [];
    const offenders = [...bannedByFile.values()].filter(f => !allowed.some(a => f.file.startsWith(a)));
    const javaOffenders = offenders.filter(f => f.file.endsWith('.java'));
    console.log('Files still mentioning MongoDB or GridFS: ' + offenders.length + ' (java ' + javaOffenders.length + ')');
    console.log('Stored types: ' + summary.documents + ', repositories: ' + summary.repositories + ', template files: ' + summary.templateFiles + ', GridFS files: ' + summary.gridFsFiles);
    process.exit(offenders.length === 0 ? 0 : 1);
}

const esc = s => String(s).replace(/\|/g, '\\|');
const md = [];
md.push('# PostgreSQL migration ledger');
md.push('');
md.push('Generated by `node scripts/migration/mongo-ledger.mjs`. Do not edit by hand. Re-run after every conversion step.');
md.push('Counts come from searching the source, not from memory. Test counts and "operation" counts are approximate where noted.');
md.push('');
md.push('## Totals');
md.push('');
md.push('| Item | Count |');
md.push('|---|---|');
md.push('| Stored document classes (`@Document`) | ' + summary.documents + ' |');
md.push('| Distinct collection names from those classes | ' + summary.distinctCollectionsFromDocuments + ' |');
md.push('| Collections mapped by more than one class | ' + summary.sharedCollections.length + ' |');
md.push('| Repositories | ' + summary.repositories + ' |');
md.push('| Main files using `MongoTemplate` / `MongoOperations` | ' + summary.templateFiles + ' |');
md.push('| Main files using GridFS | ' + summary.gridFsFiles + ' |');
md.push('| Main files using `ObjectId` | ' + summary.objectIdFiles + ' |');
md.push('| Main files importing `org.bson` / `com.mongodb` | ' + summary.rawDriverFiles + ' |');
md.push('| Mongo-specific config / initializer / migration classes | ' + summary.configClasses + ' |');
md.push('| Test files touching Mongo types | ' + summary.testFilesTouchingMongoTypes + ' |');
md.push('| Non-Java, non-test files mentioning MongoDB | ' + summary.nonJavaFilesMentioningMongo + ' |');
md.push('| Collection names used as strings with no `@Document` class | ' + summary.rawOnlyCollections.length + ' |');
md.push('');
md.push('By module (documents / repositories / template files / GridFS files / tests):');
md.push('');
md.push('| Module | Docs | Repos | Template | GridFS | Tests |');
md.push('|---|---|---|---|---|---|');
const mods = new Set([...Object.keys(summary.byModule.documents), ...Object.keys(summary.byModule.repositories), ...Object.keys(summary.byModule.templateFiles), ...Object.keys(summary.byModule.gridFsFiles), ...Object.keys(summary.byModule.tests)]);
for (const mname of [...mods].sort()) {
    md.push('| ' + mname + ' | ' + (summary.byModule.documents[mname] || 0) + ' | ' + (summary.byModule.repositories[mname] || 0) + ' | ' + (summary.byModule.templateFiles[mname] || 0) + ' | ' + (summary.byModule.gridFsFiles[mname] || 0) + ' | ' + (summary.byModule.tests[mname] || 0) + ' |');
}
md.push('');
md.push('## Collections');
md.push('');
md.push('Status columns: C = converted, T = real-database test, M = migrated by the tool, R = reconciled. Tick by hand when verified.');
md.push('');
md.push('| Collection | Class | Module | Id type | Indexes (plain / compound) | TTL | Unique | Sparse | Map fields | Date types | C | T | M | R |');
md.push('|---|---|---|---|---|---|---|---|---|---|---|---|---|---|');
for (const d of documents.slice().sort((a, b) => a.collection.localeCompare(b.collection) || a.module.localeCompare(b.module))) {
    md.push('| `' + d.collection + '` | ' + d.class + ' | ' + d.module + ' | ' + (d.idType || '?') + ' | ' + d.indexed + ' / ' + d.compoundIndexes + ' | ' + (d.ttl ? 'yes' : '') + ' | ' + (d.unique ? 'yes' : '') + ' | ' + (d.sparse ? 'yes' : '') + ' | ' + d.nestedMaps + ' | ' + d.dateTypes.join(', ') + ' | [ ] | [ ] | [ ] | [ ] |');
}
md.push('');
md.push('### Collections mapped by more than one class');
md.push('');
for (const s of summary.sharedCollections) md.push('- `' + s.collection + '`: ' + s.classes.join(', '));
md.push('');
md.push('### Collection names used as strings with no `@Document` class');
md.push('');
md.push('These are the easiest to miss. The compiler will not flag them.');
md.push('');
for (const [name, files] of rawOnlyCollections) md.push('- `' + name + '`: ' + [...new Set(files)].join(', '));
md.push('');
md.push('## Repositories');
md.push('');
md.push('| Repository | Entity | Module | Methods (approx) | `@Query` | `deleteBy` | IgnoreCase | Nested path (`A_b`) | C | T |');
md.push('|---|---|---|---|---|---|---|---|---|---|');
for (const r of repositories.slice().sort((a, b) => a.module.localeCompare(b.module) || a.interface.localeCompare(b.interface))) {
    md.push('| ' + r.interface + ' | ' + r.entity + ' | ' + r.module + ' | ' + r.methods + ' | ' + r.queryAnnotations + ' | ' + r.deleteBy + ' | ' + r.ignoreCase + ' | ' + r.nestedPath + ' | [ ] | [ ] |');
}
md.push('');
md.push('## Direct database calls (`MongoTemplate` / `MongoOperations`)');
md.push('');
md.push('`atomic` marks files that use find-and-modify, find-and-replace or upsert, which need single-statement SQL. Generic operation counts (`find`, `save` and so on) also include repository calls in the same file, so treat them as upper bounds.');
md.push('');
md.push('| File | Module | Distinct operations | Collections named as strings | Criteria uses | atomic | C | T |');
md.push('|---|---|---|---|---|---|---|---|');
for (const t of templateFiles.slice().sort((a, b) => a.module.localeCompare(b.module) || a.file.localeCompare(b.file))) {
    md.push('| ' + t.file + ' | ' + t.module + ' | ' + esc(Object.entries(t.ops).map(([k, v]) => k + 'x' + v).join(', ')) + ' | ' + esc(t.stringCollections.join(', ')) + ' | ' + t.criteria + ' | ' + (t.hotAtomic ? 'yes' : '') + ' | [ ] | [ ] |');
}
md.push('');
md.push('## File storage (GridFS)');
md.push('');
md.push('| File | Module | Operations | Metadata keys used | Buffers whole file | C | T |');
md.push('|---|---|---|---|---|---|---|');
for (const g of gridFsFiles.slice().sort((a, b) => a.module.localeCompare(b.module) || a.file.localeCompare(b.file))) {
    md.push('| ' + g.file + ' | ' + g.module + ' | ' + esc(Object.entries(g.ops).map(([k, v]) => k + 'x' + v).join(', ')) + ' | ' + esc(g.metadataKeys.join(', ')) + ' | ' + (g.buffers ? 'yes' : '') + ' | [ ] | [ ] |');
}
md.push('');
md.push('## `ObjectId` dependence');
md.push('');
md.push('| File | Module | `new ObjectId` | `isValid` | hex / getObjectId |');
md.push('|---|---|---|---|---|');
for (const o of objectIdFiles.slice().sort((a, b) => a.file.localeCompare(b.file))) {
    md.push('| ' + o.file + ' | ' + o.module + ' | ' + o.newObjectId + ' | ' + o.isValid + ' | ' + o.toHex + ' |');
}
md.push('');
md.push('## Mongo-specific configuration and migration classes');
md.push('');
for (const c of configFiles.sort((a, b) => a.file.localeCompare(b.file))) md.push('- ' + c.file);
md.push('');
md.push('## Raw driver (`org.bson`, `com.mongodb`) imports in main code');
md.push('');
for (const r of rawDriverFiles.sort((a, b) => a.file.localeCompare(b.file))) md.push('- ' + r.file + ': ' + r.imports.join(', '));
md.push('');
md.push('## Non-Java and non-test files that mention MongoDB');
md.push('');
md.push('| File | Module | Mentions | Sample |');
md.push('|---|---|---|---|');
for (const f of nonJava.sort((a, b) => b.total - a.total)) {
    md.push('| ' + f.file + ' | ' + f.module + ' | ' + f.total + ' | ' + esc((f.sampleLines[0] || '').slice(0, 110)) + ' |');
}
md.push('');
md.push('## Test files touching Mongo types');
md.push('');
md.push('| File | Module | Kinds |');
md.push('|---|---|---|');
for (const t of testFiles.sort((a, b) => a.file.localeCompare(b.file))) md.push('| ' + t.file + ' | ' + t.module + ' | ' + t.kinds.join(', ') + ' |');
md.push('');

fs.mkdirSync(path.dirname(OUT_MD), { recursive: true });
fs.writeFileSync(OUT_MD, md.join('\n'));
fs.writeFileSync(OUT_JSON, JSON.stringify({ summary, documents, repositories, templateFiles, gridFsFiles, objectIdFiles, rawDriverFiles, configFiles, testFiles, nonJava, rawOnlyCollections: summary.rawOnlyCollections }, null, 1));

console.log(JSON.stringify({
    documents: summary.documents,
    collections: summary.distinctCollectionsFromDocuments,
    shared: summary.sharedCollections.length,
    repositories: summary.repositories,
    templateFiles: summary.templateFiles,
    gridFsFiles: summary.gridFsFiles,
    objectIdFiles: summary.objectIdFiles,
    rawDriverFiles: summary.rawDriverFiles,
    configClasses: summary.configClasses,
    testFiles: summary.testFilesTouchingMongoTypes,
    nonJavaFiles: summary.nonJavaFilesMentioningMongo,
    rawOnlyCollections: summary.rawOnlyCollections.length
}, null, 1));
