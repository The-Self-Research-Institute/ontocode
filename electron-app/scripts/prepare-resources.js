/**
 * prepare-resources.js
 *
 * Copies / downloads backend binaries into electron-app/resources/backend/
 * before an electron-builder dist run.
 *
 * Run:  node scripts/prepare-resources.js
 *       npm run prepare-resources
 *
 * What it does:
 *   1. Copy owl-editor.jar from ontology-editor/target/
 *   2. Copy fuseki-server.jar from fuseki-docker/
 *   3. Copy mongod binary from data/mongodb/bin/<platform>/
 *   4. Bundle a minimal JRE via jlink (preferred) or download Temurin 17 JRE
 *
 * Result layout inside electron-app/resources/backend/:
 *
 *   jars/
 *     owl-editor.jar
 *     fuseki-server.jar
 *   mongodb/
 *     win32/   mongod.exe
 *     darwin/  mongod
 *     linux/   mongod
 *   jre/
 *     bin/java[.exe]   ← used by ServiceManager; no system Java required
 *     ...
 */

const fs            = require('fs');
const path          = require('path');
const { execSync, execFileSync } = require('child_process');
const https         = require('https');
const { pipeline }  = require('stream/promises');
const zlib          = require('zlib');

const REPO_ROOT  = path.resolve(__dirname, '..', '..');
const RESOURCES  = path.resolve(__dirname, '..', 'resources', 'backend');
const JARS_DIR   = path.join(RESOURCES, 'jars');
const JRE_DIR    = path.join(RESOURCES, 'jre');

// ── Minimal modules needed for Spring Boot + Jena Fuseki ────────────────────
const JLINK_MODULES = [
    'java.base',
    'java.compiler',
    'java.desktop',
    'java.instrument',
    'java.logging',
    'java.management',
    'java.management.rmi',
    'java.naming',
    'java.net.http',
    'java.prefs',
    'java.rmi',
    'java.scripting',
    'java.security.jgss',
    'java.security.sasl',
    'java.sql',
    'java.xml',
    'java.xml.crypto',
    'jdk.httpserver',
    'jdk.jfr',
    'jdk.management',
    'jdk.unsupported',
    'jdk.zipfs',
].join(',');

// ── Temurin 17 JRE download URLs per platform ────────────────────────────────
const TEMURIN_URLS = {
    win32:  'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse',
    darwin: 'https://api.adoptium.net/v3/binary/latest/17/ga/mac/x64/jre/hotspot/normal/eclipse',
    linux:  'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jre/hotspot/normal/eclipse',
};

function ensureDir(d) { if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true }); }

function copyIfExists(src, dest, label) {
    if (fs.existsSync(src)) {
        ensureDir(path.dirname(dest));
        fs.copyFileSync(src, dest);
        console.log(`  ✓  Copied ${label}`);
        return true;
    }
    console.warn(`  ⚠  NOT FOUND: ${label}  (expected: ${src})`);
    return false;
}

// ── Step 1: Desktop JAR (auth + editor + plugin) ─────────────────────────────
function copyDesktopJar() {
    console.log('\n[1/4] Desktop JAR (ontology-desktop)');
    const src = path.join(REPO_ROOT, 'ontology-desktop', 'target', 'ontology-desktop-1.0.0.jar');
    const dest = path.join(JARS_DIR, 'desktop.jar');
    const ok = copyIfExists(src, dest, 'desktop.jar');
    if (!ok) {
        console.log('       Build:  mvn -pl ontology-desktop package -DskipTests');
        console.log('       Or:     node scripts/build-desktop.js --java');
    }
    return ok;
}

// Legacy name kept for older docs — owl-editor is no longer started by ServiceManager.
function copyOwlEditorJar() {
    copyDesktopJar();
}

// ── Step 2: Fuseki JAR ────────────────────────────────────────────────────────
function copyFusekiJar() {
    console.log('\n[2/4] Fuseki JAR');
    const ok = copyIfExists(
        path.join(REPO_ROOT, 'fuseki-docker', 'fuseki-server.jar'),
        path.join(JARS_DIR, 'fuseki-server.jar'),
        'fuseki-server.jar',
    );
    if (!ok) {
        console.log('       Extract from Docker image:');
        console.log('         docker create jena/fuseki:latest tmp_fuseki');
        console.log('         docker cp tmp_fuseki:/jena-fuseki/fuseki-server.jar fuseki-docker/');
        console.log('         docker rm tmp_fuseki');
    }
}

// ── Step 3: MongoDB binary ────────────────────────────────────────────────────
function copyMongod() {
    console.log('\n[3/4] MongoDB binary');
    ['win32', 'darwin', 'linux'].forEach((platform) => {
        const ext  = platform === 'win32' ? '.exe' : '';
        const src  = path.join(REPO_ROOT, 'data', 'mongodb', 'bin', platform, `mongod${ext}`);
        const dest = path.join(RESOURCES, 'mongodb', platform, `mongod${ext}`);
        copyIfExists(src, dest, `mongodb/${platform}/mongod${ext}`);
    });
    console.log('       Download: https://www.mongodb.com/try/download/community (zip/tgz)');
    console.log('       Extract mongod[.exe] → data/mongodb/bin/<platform>/');
}

// ── Step 4: JRE (jlink → download fallback) ──────────────────────────────────
async function bundleJre() {
    console.log('\n[4/4] JRE (bundled — no system Java required at runtime)');

    // Already there?
    const javaBin = path.join(JRE_DIR, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(javaBin)) {
        try {
            const ver = execFileSync(javaBin, ['--version'], { encoding: 'utf8', timeout: 5000 });
            console.log(`  ✓  Bundled JRE already present: ${ver.split('\n')[0].trim()}`);
            return;
        } catch (_) {
            console.warn('  ⚠  Existing JRE unreadable — re-creating');
            fs.rmSync(JRE_DIR, { recursive: true, force: true });
        }
    }

    // Try jlink first (fastest, smallest — needs JDK on the build machine)
    if (await tryJlink()) return;

    // Fall back to downloading Temurin JRE
    await downloadTemurin();
}

async function tryJlink() {
    let jlinkBin = 'jlink';
    if (process.env.JAVA_HOME) {
        const candidate = path.join(process.env.JAVA_HOME, 'bin',
            process.platform === 'win32' ? 'jlink.exe' : 'jlink');
        if (fs.existsSync(candidate)) jlinkBin = candidate;
    }

    try {
        execFileSync(jlinkBin, ['--version'], { timeout: 5000, encoding: 'utf8' });
    } catch {
        console.log('  ℹ  jlink not found — will download pre-built JRE instead');
        return false;
    }

    console.log('  → Creating minimal JRE with jlink (this takes ~30 s)…');
    try {
        execSync(
            `"${jlinkBin}" --add-modules ${JLINK_MODULES} --output "${JRE_DIR}" --strip-debug --no-man-pages --no-header-files --compress=2`,
            { stdio: 'inherit', timeout: 120_000 },
        );
        console.log('  ✓  Minimal JRE created via jlink (~60-80 MB)');
        return true;
    } catch (err) {
        console.warn(`  ⚠  jlink failed: ${err.message}`);
        if (fs.existsSync(JRE_DIR)) fs.rmSync(JRE_DIR, { recursive: true, force: true });
        return false;
    }
}

async function downloadTemurin() {
    const platform = process.platform;
    const url = TEMURIN_URLS[platform];
    if (!url) {
        console.error(`  ✗  No Temurin URL configured for platform "${platform}"`);
        console.error('     Add the URL to TEMURIN_URLS in prepare-resources.js');
        return;
    }

    console.log(`  → Downloading Temurin 17 JRE for ${platform}…`);
    console.log(`     ${url}`);

    ensureDir(JRE_DIR);

    const archiveExt = platform === 'win32' ? '.zip' : '.tar.gz';
    const archivePath = path.join(RESOURCES, `temurin-jre${archiveExt}`);

    try {
        await downloadFile(url, archivePath);
        console.log('  → Extracting JRE…');
        extractJreArchive(archivePath, JRE_DIR, platform);
        fs.rmSync(archivePath, { force: true });
        console.log('  ✓  Temurin 17 JRE extracted to resources/backend/jre/');
    } catch (err) {
        console.error(`  ✗  Download/extract failed: ${err.message}`);
        console.error('     Manual option: download from https://adoptium.net/temurin/releases/?version=17');
        console.error(`     Extract to: electron-app/resources/backend/jre/`);
        console.error('     The jre/ folder must contain bin/java (or bin/java.exe on Windows)');
    }
}

function downloadFile(url, dest) {
    return new Promise((resolve, reject) => {
        const file = fs.createWriteStream(dest);
        function follow(u) {
            https.get(u, { headers: { 'User-Agent': 'ontocode-build/1.0' } }, (res) => {
                if (res.statusCode === 301 || res.statusCode === 302 || res.statusCode === 307) {
                    file.close();
                    return follow(res.headers.location);
                }
                if (res.statusCode !== 200) {
                    file.close();
                    return reject(new Error(`HTTP ${res.statusCode}`));
                }
                const total = parseInt(res.headers['content-length'] || '0', 10);
                let received = 0;
                res.on('data', (chunk) => {
                    received += chunk.length;
                    if (total) {
                        const pct = Math.round(received / total * 100);
                        process.stdout.write(`\r     Progress: ${pct}%   `);
                    }
                });
                res.pipe(file);
                file.on('finish', () => { process.stdout.write('\n'); file.close(resolve); });
                file.on('error', reject);
            }).on('error', reject);
        }
        follow(url);
    });
}

function extractJreArchive(archivePath, targetDir, platform) {
    ensureDir(targetDir);
    if (platform === 'win32') {
        // PowerShell Expand-Archive (built-in on Windows 10+)
        execSync(
            `powershell -NoProfile -Command "Expand-Archive -Path '${archivePath}' -DestinationPath '${targetDir}' -Force"`,
            { stdio: 'inherit', timeout: 120_000 },
        );
        // Temurin extracts to jdk-17.x.x-jre\ — flatten it
        flattenSingleSubdir(targetDir);
    } else {
        // tar with strip-components to skip the top-level jdk-17.x.x-jre/ dir
        execSync(
            `tar -xzf "${archivePath}" -C "${targetDir}" --strip-components=1`,
            { stdio: 'inherit', timeout: 120_000 },
        );
    }
}

/** If targetDir contains exactly one subdirectory, move its contents up. */
function flattenSingleSubdir(targetDir) {
    const entries = fs.readdirSync(targetDir);
    if (entries.length !== 1) return;
    const sub = path.join(targetDir, entries[0]);
    if (!fs.statSync(sub).isDirectory()) return;
    fs.readdirSync(sub).forEach(item => {
        fs.renameSync(path.join(sub, item), path.join(targetDir, item));
    });
    fs.rmdirSync(sub);
}

// ── Main ──────────────────────────────────────────────────────────────────────
async function main() {
    console.log('\n=== OntoCode Desktop — prepare-resources ===');
    ensureDir(JARS_DIR);

    copyOwlEditorJar();
    copyFusekiJar();
    copyMongod();
    await bundleJre();

    console.log('\n=== Summary ===');
    const checks = {
        'desktop.jar':       path.join(JARS_DIR, 'desktop.jar'),
        'fuseki-server.jar': path.join(JARS_DIR, 'fuseki-server.jar'),
        'JRE':               path.join(JRE_DIR, 'bin', process.platform === 'win32' ? 'java.exe' : 'java'),
    };
    let allOk = true;
    for (const [label, p] of Object.entries(checks)) {
        const ok = fs.existsSync(p);
        console.log(`  ${ok ? '✓' : '✗'}  ${label}`);
        if (!ok) allOk = false;
    }
    if (!allOk) {
        console.error('\nERROR: Missing required files. Fix the warnings above before running electron-builder.\n');
        process.exit(1);
    }
    console.log('\n✓  All required resources present — ready for: npm run dist\n');
}

main().catch(err => { console.error(err); process.exit(1); });
