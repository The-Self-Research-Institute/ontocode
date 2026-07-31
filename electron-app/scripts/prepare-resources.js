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
 *   2. Copy fuseki-server.jar from fuseki-docker/, or download the official
 *      Apache Jena Fuseki release (checksum-verified) if not present — no
 *      Docker required, so this works on a fresh machine
 *   3. Copy mongod binary from data/mongodb/bin/<platform>/, or (Windows only)
 *      download the official MongoDB Community Server release (checksum-verified)
 *      if not present. macOS/Linux still require the manual download — see the
 *      warning printed below.
 *   4. Bundle a minimal JRE via jlink (preferred) or download Temurin 21 JRE
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
const crypto        = require('crypto');

const REPO_ROOT  = path.resolve(__dirname, '..', '..');
const RESOURCES  = path.resolve(__dirname, '..', 'resources', 'backend');
const JARS_DIR   = path.join(RESOURCES, 'jars');
const JRE_DIR    = path.join(RESOURCES, 'jre');

// Cross-building for a platform other than the one this script runs on (e.g.
// preparing a Linux bundle from Windows for an electron-builder --linux run).
// jlink can't cross-compile — it only ever produces a runtime for the host
// it's invoked on — so when this differs from process.platform we skip jlink
// entirely and go straight to downloading the prebuilt Temurin JRE, and fetch
// mongod for the target platform instead of copying/downloading the host's.
const TARGET_PLATFORM = process.env.TARGET_PLATFORM || process.platform;
const CROSS_BUILDING = TARGET_PLATFORM !== process.platform;

// ── Fuseki download (fallback when fuseki-docker/fuseki-server.jar is absent) ──
// Same version + checksum pinned in fuseki-docker/Dockerfile — keep in sync.
const FUSEKI_VERSION = '6.1.0';
const FUSEKI_SHA512 = '75457f45d14397876a41ed51abe7ae5d2f1e708dfe1315765f858158bc5c6813bc036ec1539ddc4dffd26201f5cc31fadec299ca5c3dc2548b723513ed31d326';
const FUSEKI_MIRROR_URL = `https://www.apache.org/dyn/mirrors/mirrors.cgi?action=download&filename=jena/binaries/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz`;
const FUSEKI_ARCHIVE_URL = `https://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz`;

// ── MongoDB download (fallback when data/mongodb/bin/win32/mongod.exe is absent) ──
// Latest 6.0.x as of this writing — matches the mongo:6 image used by docker-compose.
// Checksum pulled from the official .sha256 file MongoDB publishes alongside the release.
const MONGODB_VERSION = '6.0.29';
const MONGODB_WIN32_URL = `https://fastdl.mongodb.org/windows/mongodb-windows-x86_64-${MONGODB_VERSION}.zip`;
const MONGODB_WIN32_SHA256 = 'abfd03e5e02c962004e0b46d47777cdd3bca767b1a200dcafc2194cc5415cd55';

// Linux builds are distro-specific (glibc/OpenSSL version dependent), unlike the
// generic Windows/Mac builds — this one targets Ubuntu 22.04 (jammy), the default
// WSL distro. Checksum pulled from the official .sha256 MongoDB publishes alongside
// the release (fastdl.mongodb.org/linux/mongodb-linux-x86_64-ubuntu2204-<ver>.tgz.sha256).
const MONGODB_LINUX_UBUNTU2204_URL = `https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-ubuntu2204-${MONGODB_VERSION}.tgz`;
const MONGODB_LINUX_UBUNTU2204_SHA256 = '46de5a28be8066e0c44b60e9919e5edd00c28f55fc187f8e0c60ab38dedc9054';

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

// ── Temurin 21 JRE download URLs per platform ────────────────────────────────
const TEMURIN_URLS = {
    win32:  'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse',
    darwin: 'https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jre/hotspot/normal/eclipse',
    linux:  'https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse',
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
async function copyFusekiJar() {
    console.log('\n[2/4] Fuseki JAR');
    const cachedSrc = path.join(REPO_ROOT, 'fuseki-docker', 'fuseki-server.jar');
    const ok = copyIfExists(cachedSrc, path.join(JARS_DIR, 'fuseki-server.jar'), 'fuseki-server.jar');
    if (!ok) {
        console.log(`  → Not found locally — downloading Apache Jena Fuseki ${FUSEKI_VERSION}…`);
        await downloadFuseki(cachedSrc);
    }
}

/**
 * Download the official Fuseki binary distribution (no Docker required —
 * this is what makes prepare-resources work on a machine that only has
 * Node/npm, e.g. a fresh clone or a machine building the installer). Mirrors
 * the same version + checksum pinned in fuseki-docker/Dockerfile.
 */
async function downloadFuseki(cachedSrc) {
    const archivePath = path.join(RESOURCES, `apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz`);
    ensureDir(RESOURCES);

    try {
        // archive.apache.org is a single stable host (verified reachable and fast);
        // the mirrors.cgi redirect is more convenient bandwidth-wise when it works,
        // but has been observed to redirect to a mirror that accepts the connection
        // and then never responds — try the reliable host first, mirror as a bonus
        // fallback rather than the primary path.
        try {
            await downloadFile(FUSEKI_ARCHIVE_URL, archivePath);
        } catch (archiveErr) {
            console.warn(`  ⚠  archive.apache.org failed (${archiveErr.message}) — trying mirrors.cgi…`);
            await downloadFile(FUSEKI_MIRROR_URL, archivePath);
        }

        const actualSha512 = crypto.createHash('sha512').update(fs.readFileSync(archivePath)).digest('hex');
        if (actualSha512 !== FUSEKI_SHA512) {
            throw new Error(
                `Checksum mismatch for apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz\n` +
                `       expected: ${FUSEKI_SHA512}\n       got:      ${actualSha512}`
            );
        }
        console.log('  ✓  Checksum verified');

        const extractDir = path.join(RESOURCES, `_fuseki-extract-${FUSEKI_VERSION}`);
        fs.rmSync(extractDir, { recursive: true, force: true });
        ensureDir(extractDir);
        // Windows' bundled tar (bsdtar) needs two things to accept a native path here:
        //  --force-local — otherwise it reads the drive letter in "E:\..." as a
        //    "host:path" remote-archive spec ("Cannot connect to E: resolve failed").
        //  forward slashes — backslashes get mangled by its own path parsing even
        //    with --force-local ("Cannot open: No such file or directory" on a dir
        //    that does exist). Both are harmless no-ops on macOS/Linux tar.
        const tarArchivePath = archivePath.replace(/\\/g, '/');
        const tarExtractDir = extractDir.replace(/\\/g, '/');
        execSync(`tar --force-local -xzf "${tarArchivePath}" -C "${tarExtractDir}"`, { stdio: 'inherit', timeout: 120_000 });

        const topLevel = fs.readdirSync(extractDir).find((n) => n.startsWith('apache-jena-fuseki'));
        if (!topLevel) throw new Error('Extracted archive did not contain an apache-jena-fuseki* directory');
        const extractedJar = path.join(extractDir, topLevel, 'fuseki-server.jar');
        if (!fs.existsSync(extractedJar)) throw new Error(`fuseki-server.jar not found inside ${topLevel}`);

        ensureDir(JARS_DIR);
        fs.copyFileSync(extractedJar, path.join(JARS_DIR, 'fuseki-server.jar'));
        // Also cache it at the path copyFusekiJar() checks first, so future
        // runs (and the Docker build, which expects this layout) skip the download.
        ensureDir(path.dirname(cachedSrc));
        fs.copyFileSync(extractedJar, cachedSrc);
        console.log('  ✓  Downloaded and installed fuseki-server.jar');

        fs.rmSync(extractDir, { recursive: true, force: true });
        fs.rmSync(archivePath, { force: true });
    } catch (err) {
        console.error(`  ✗  Fuseki download failed: ${err.message}`);
        console.error('     Manual fallback — extract from the Docker image:');
        console.error('       docker create jena/fuseki:latest tmp_fuseki');
        console.error('       docker cp tmp_fuseki:/jena-fuseki/fuseki-server.jar fuseki-docker/');
        console.error('       docker rm tmp_fuseki');
        fs.rmSync(archivePath, { force: true });
    }
}

// ── Step 3: MongoDB binary ────────────────────────────────────────────────────
async function copyMongod() {
    console.log('\n[3/4] MongoDB binary');
    let anyMissing = false;
    for (const platform of ['win32', 'darwin', 'linux']) {
        const ext  = platform === 'win32' ? '.exe' : '';
        const cachedSrc = path.join(REPO_ROOT, 'data', 'mongodb', 'bin', platform, `mongod${ext}`);
        const dest = path.join(RESOURCES, 'mongodb', platform, `mongod${ext}`);
        let ok = copyIfExists(cachedSrc, dest, `mongodb/${platform}/mongod${ext}`);
        if (!ok && platform === 'win32') {
            console.log(`  → Not found locally — downloading MongoDB Community Server ${MONGODB_VERSION}…`);
            ok = await downloadMongodWin32(cachedSrc, dest);
        }
        if (!ok && platform === 'linux' && (platform === TARGET_PLATFORM || CROSS_BUILDING)) {
            console.log(`  → Not found locally — downloading MongoDB Community Server ${MONGODB_VERSION} (Ubuntu 22.04)…`);
            ok = await downloadMongodLinuxUbuntu2204(cachedSrc, dest);
        }
        if (!ok) anyMissing = true;
    }
    if (anyMissing) {
        console.log('       macOS/Linux still require the manual download:');
        console.log('       Download: https://www.mongodb.com/try/download/community (zip/tgz)');
        console.log('       Extract mongod[.exe] → data/mongodb/bin/<platform>/');
    }
}

/**
 * Download the official MongoDB Community Server Windows build (checksum-verified)
 * so a fresh Windows machine doesn't need a manual download — this was a recurring
 * blocker: prepare-resources previously required the user to manually fetch and
 * place mongod.exe before every build. macOS/Linux are left manual (see copyMongod)
 * since their archive layout/checksums aren't verified here.
 */
async function downloadMongodWin32(cachedSrc, dest) {
    const archivePath = path.join(RESOURCES, `mongodb-windows-x86_64-${MONGODB_VERSION}.zip`);
    ensureDir(RESOURCES);

    try {
        await downloadFile(MONGODB_WIN32_URL, archivePath);

        const actualSha256 = crypto.createHash('sha256').update(fs.readFileSync(archivePath)).digest('hex');
        if (actualSha256 !== MONGODB_WIN32_SHA256) {
            throw new Error(
                `Checksum mismatch for mongodb-windows-x86_64-${MONGODB_VERSION}.zip\n` +
                `       expected: ${MONGODB_WIN32_SHA256}\n       got:      ${actualSha256}`
            );
        }
        console.log('  ✓  Checksum verified');

        const extractDir = path.join(RESOURCES, `_mongodb-extract-${MONGODB_VERSION}`);
        fs.rmSync(extractDir, { recursive: true, force: true });
        ensureDir(extractDir);
        execSync(
            `powershell -NoProfile -Command "Expand-Archive -Path '${archivePath}' -DestinationPath '${extractDir}' -Force"`,
            { stdio: 'inherit', timeout: 120_000 },
        );

        const extractedExe = findFileRecursive(extractDir, 'mongod.exe');
        if (!extractedExe) throw new Error(`mongod.exe not found anywhere inside the extracted archive`);

        ensureDir(path.dirname(dest));
        fs.copyFileSync(extractedExe, dest);
        // Also cache it where copyMongod() checks first, so future runs skip the download.
        ensureDir(path.dirname(cachedSrc));
        fs.copyFileSync(extractedExe, cachedSrc);
        console.log('  ✓  Downloaded and installed mongod.exe');

        fs.rmSync(extractDir, { recursive: true, force: true });
        fs.rmSync(archivePath, { force: true });
        return true;
    } catch (err) {
        console.error(`  ✗  MongoDB download failed: ${err.message}`);
        fs.rmSync(archivePath, { force: true });
        return false;
    }
}

/**
 * Download the official MongoDB Community Server Linux build (checksum-verified),
 * for cross-building a Linux bundle from a non-Linux host — see downloadMongodWin32
 * for the same pattern. Ubuntu 22.04 specifically (Linux builds are distro-specific
 * unlike Windows/Mac); tar preserves the executable bit through extraction, and we
 * chmod it explicitly afterward since the host filesystem here may not honor it.
 */
async function downloadMongodLinuxUbuntu2204(cachedSrc, dest) {
    const archivePath = path.join(RESOURCES, `mongodb-linux-x86_64-ubuntu2204-${MONGODB_VERSION}.tgz`);
    ensureDir(RESOURCES);

    try {
        await downloadFile(MONGODB_LINUX_UBUNTU2204_URL, archivePath);

        const actualSha256 = crypto.createHash('sha256').update(fs.readFileSync(archivePath)).digest('hex');
        if (actualSha256 !== MONGODB_LINUX_UBUNTU2204_SHA256) {
            throw new Error(
                `Checksum mismatch for mongodb-linux-x86_64-ubuntu2204-${MONGODB_VERSION}.tgz\n` +
                `       expected: ${MONGODB_LINUX_UBUNTU2204_SHA256}\n       got:      ${actualSha256}`
            );
        }
        console.log('  ✓  Checksum verified');

        const extractDir = path.join(RESOURCES, `_mongodb-extract-linux-${MONGODB_VERSION}`);
        fs.rmSync(extractDir, { recursive: true, force: true });
        ensureDir(extractDir);
        const tarArchivePath = archivePath.replace(/\\/g, '/');
        const tarExtractDir = extractDir.replace(/\\/g, '/');
        execSync(`tar --force-local -xzf "${tarArchivePath}" -C "${tarExtractDir}"`, { stdio: 'inherit', timeout: 120_000 });

        const extractedBin = findFileRecursive(extractDir, 'mongod');
        if (!extractedBin) throw new Error(`mongod not found anywhere inside the extracted archive`);

        ensureDir(path.dirname(dest));
        fs.copyFileSync(extractedBin, dest);
        fs.chmodSync(dest, 0o755);
        // Also cache it where copyMongod() checks first, so future runs skip the download.
        ensureDir(path.dirname(cachedSrc));
        fs.copyFileSync(extractedBin, cachedSrc);
        fs.chmodSync(cachedSrc, 0o755);
        console.log('  ✓  Downloaded and installed mongod (linux/ubuntu2204)');

        fs.rmSync(extractDir, { recursive: true, force: true });
        fs.rmSync(archivePath, { force: true });
        return true;
    } catch (err) {
        console.error(`  ✗  MongoDB (Linux) download failed: ${err.message}`);
        fs.rmSync(archivePath, { force: true });
        return false;
    }
}

/** Recursively search a directory tree for the first file matching `name`. */
function findFileRecursive(dir, name) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            const found = findFileRecursive(full, name);
            if (found) return found;
        } else if (entry.name.toLowerCase() === name.toLowerCase()) {
            return full;
        }
    }
    return null;
}

// ── Step 4: JRE (jlink → download fallback) ──────────────────────────────────
async function bundleJre() {
    console.log('\n[4/4] JRE (bundled — no system Java required at runtime)');

    // Already there? Check for the TARGET platform's binary name specifically — a
    // java.exe left over from a prior Windows prepare must not be mistaken for a
    // valid Linux JRE (it naturally won't exist under the "java" name we look for
    // when cross-building, so this falls through to a fresh fetch correctly).
    const javaBin = path.join(JRE_DIR, 'bin', TARGET_PLATFORM === 'win32' ? 'java.exe' : 'java');
    if (!CROSS_BUILDING && fs.existsSync(javaBin)) {
        try {
            const ver = execFileSync(javaBin, ['--version'], { encoding: 'utf8', timeout: 5000 });
            console.log(`  ✓  Bundled JRE already present: ${ver.split('\n')[0].trim()}`);
            return;
        } catch (_) {
            console.warn('  ⚠  Existing JRE unreadable — re-creating');
            fs.rmSync(JRE_DIR, { recursive: true, force: true });
        }
    } else if (CROSS_BUILDING && fs.existsSync(javaBin)) {
        // Can't exec-verify a foreign-platform binary from here — but if the
        // TARGET platform's own binary name is already present (not just any
        // leftover JRE dir), trust it rather than re-fetching every single run.
        console.log(`  ✓  Bundled JRE for ${TARGET_PLATFORM} already present (existence check only — can't exec-verify a foreign binary)`);
        return;
    }

    // jlink can't cross-compile — only try it when building for the host's own platform.
    if (!CROSS_BUILDING && await tryJlink()) return;

    // Fall back to downloading Temurin JRE (the only option when cross-building)
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
    const platform = TARGET_PLATFORM;
    const url = TEMURIN_URLS[platform];
    if (!url) {
        console.error(`  ✗  No Temurin URL configured for platform "${platform}"`);
        console.error('     Add the URL to TEMURIN_URLS in prepare-resources.js');
        return;
    }

    console.log(`  → Downloading Temurin 21 JRE for ${platform}…`);
    console.log(`     ${url}`);

    ensureDir(JRE_DIR);

    const archiveExt = platform === 'win32' ? '.zip' : '.tar.gz';
    const archivePath = path.join(RESOURCES, `temurin-jre${archiveExt}`);

    try {
        await downloadFile(url, archivePath);
        console.log('  → Extracting JRE…');
        extractJreArchive(archivePath, JRE_DIR, platform);
        fs.rmSync(archivePath, { force: true });
        console.log('  ✓  Temurin 21 JRE extracted to resources/backend/jre/');
    } catch (err) {
        console.error(`  ✗  Download/extract failed: ${err.message}`);
        console.error('     Manual option: download from https://adoptium.net/temurin/releases/?version=21');
        console.error(`     Extract to: electron-app/resources/backend/jre/`);
        console.error('     The jre/ folder must contain bin/java (or bin/java.exe on Windows)');
    }
}

// Apache's mirrors.cgi redirect can hand off to a mirror that accepts the
// connection and then never responds — https.get has no default timeout, so
// that hangs the whole script forever instead of failing over. 20s covers a
// slow-but-alive connection; anything stalled longer than that is dead.
const DOWNLOAD_TIMEOUT_MS = 20_000;

function downloadFile(url, dest, redirectsLeft = 5) {
    return new Promise((resolve, reject) => {
        const file = fs.createWriteStream(dest);
        function follow(u, remaining) {
            const req = https.get(u, { headers: { 'User-Agent': 'ontocode-build/1.0' } }, (res) => {
                if (res.statusCode === 301 || res.statusCode === 302 || res.statusCode === 307) {
                    if (remaining <= 0) {
                        file.close();
                        return reject(new Error('Too many redirects'));
                    }
                    file.close();
                    return follow(res.headers.location, remaining - 1);
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
            });
            req.setTimeout(DOWNLOAD_TIMEOUT_MS, () => {
                req.destroy(new Error(`Timed out after ${DOWNLOAD_TIMEOUT_MS / 1000}s connecting to ${u}`));
            });
            req.on('error', reject);
        }
        follow(url, redirectsLeft);
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
        // Temurin extracts to jdk-21.x.x-jre\ — flatten it
        flattenSingleSubdir(targetDir);
    } else {
        // tar with strip-components to skip the top-level jdk-21.x.x-jre/ dir.
        // --force-local + forward slashes: same Windows-bsdtar workaround as the
        // Fuseki/Linux-mongo downloads above — without it, a Windows path like
        // "E:\..." gets misread as a "host:path" remote-archive spec. Harmless
        // no-ops on macOS/Linux tar, so this is safe regardless of the host.
        const tarArchivePath = archivePath.replace(/\\/g, '/');
        const tarTargetDir = targetDir.replace(/\\/g, '/');
        execSync(
            `tar --force-local -xzf "${tarArchivePath}" -C "${tarTargetDir}" --strip-components=1`,
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
    if (CROSS_BUILDING) {
        console.log(`    Cross-building for TARGET_PLATFORM=${TARGET_PLATFORM} (host is ${process.platform})`);
    }
    ensureDir(JARS_DIR);

    copyOwlEditorJar();
    await copyFusekiJar();
    await copyMongod();
    await bundleJre();

    console.log('\n=== Summary ===');
    const checks = {
        'desktop.jar':       path.join(JARS_DIR, 'desktop.jar'),
        'fuseki-server.jar': path.join(JARS_DIR, 'fuseki-server.jar'),
        'mongod':            path.join(RESOURCES, 'mongodb', TARGET_PLATFORM, `mongod${TARGET_PLATFORM === 'win32' ? '.exe' : ''}`),
        'JRE':               path.join(JRE_DIR, 'bin', TARGET_PLATFORM === 'win32' ? 'java.exe' : 'java'),
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
