

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

const JRE17_DIR  = path.join(RESOURCES, 'jre17');

const TARGET_PLATFORM = process.env.TARGET_PLATFORM || process.platform;
const CROSS_BUILDING = TARGET_PLATFORM !== process.platform;

const TARGET_ARCH = process.env.TARGET_ARCH || process.arch;

const FUSEKI_VERSION = '6.1.0';
const FUSEKI_SHA512 = '75457f45d14397876a41ed51abe7ae5d2f1e708dfe1315765f858158bc5c6813bc036ec1539ddc4dffd26201f5cc31fadec299ca5c3dc2548b723513ed31d326';
const FUSEKI_MIRROR_URL = `https://www.apache.org/dyn/mirrors/mirrors.cgi?action=download&filename=jena/binaries/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz`;
const FUSEKI_ARCHIVE_URL = `https://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz`;

const MONGODB_VERSION = '6.0.29';
const MONGODB_WIN32_URL = `https://fastdl.mongodb.org/windows/mongodb-windows-x86_64-${MONGODB_VERSION}.zip`;
const MONGODB_WIN32_SHA256 = 'abfd03e5e02c962004e0b46d47777cdd3bca767b1a200dcafc2194cc5415cd55';

const MONGODB_LINUX_UBUNTU2204_URL = `https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-ubuntu2204-${MONGODB_VERSION}.tgz`;
const MONGODB_LINUX_UBUNTU2204_SHA256 = '46de5a28be8066e0c44b60e9919e5edd00c28f55fc187f8e0c60ab38dedc9054';

const MONGODB_LINUX_ARM64_UBUNTU2204_URL = `https://fastdl.mongodb.org/linux/mongodb-linux-aarch64-ubuntu2204-${MONGODB_VERSION}.tgz`;
const MONGODB_LINUX_ARM64_UBUNTU2204_SHA256 = '81003080fd01a95dc7bbc08a5e62c80d22b55df0ce5df6b674c2ec915a4b825b';

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

const TEMURIN_URLS = {
    win32:  'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse',
    darwin: 'https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jre/hotspot/normal/eclipse',
    linux:  'https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse',
};

const TEMURIN_LINUX_ARM64_URL = 'https://api.adoptium.net/v3/binary/latest/21/ga/linux/aarch64/jre/hotspot/normal/eclipse';

const TEMURIN_17_URLS = {
    win32:  'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse',
    darwin: 'https://api.adoptium.net/v3/binary/latest/17/ga/mac/x64/jre/hotspot/normal/eclipse',
    linux:  'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jre/hotspot/normal/eclipse',
};

const TEMURIN_17_LINUX_ARM64_URL = 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jre/hotspot/normal/eclipse';

const REQUIRED_JRE_MAJOR = 21;

function javaMajorVersion(javaBin) {
    try {
        const out = execFileSync(javaBin, ['--version'], { encoding: 'utf8', timeout: 5000 });
        const m = out.match(/(?:openjdk|java)\s+(\d+)/i);
        return m ? parseInt(m[1], 10) : null;
    } catch (_) {
        return null;
    }
}

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

function copyOwlEditorJar() {
    copyDesktopJar();
}

async function copyFusekiJar() {
    console.log('\n[2/4] Fuseki JAR');
    const cachedSrc = path.join(REPO_ROOT, 'fuseki-docker', 'fuseki-server.jar');
    const ok = copyIfExists(cachedSrc, path.join(JARS_DIR, 'fuseki-server.jar'), 'fuseki-server.jar');
    if (!ok) {
        console.log(`  → Not found locally — downloading Apache Jena Fuseki ${FUSEKI_VERSION}…`);
        await downloadFuseki(cachedSrc);
    }
}

async function downloadFuseki(cachedSrc) {
    const archivePath = path.join(RESOURCES, `apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz`);
    ensureDir(RESOURCES);

    try {

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

        const tarArchivePath = archivePath.replace(/\\/g, '/');
        const tarExtractDir = extractDir.replace(/\\/g, '/');
        execSync(`tar --force-local -xzf "${tarArchivePath}" -C "${tarExtractDir}"`, { stdio: 'inherit', timeout: 120_000 });

        const topLevel = fs.readdirSync(extractDir).find((n) => n.startsWith('apache-jena-fuseki'));
        if (!topLevel) throw new Error('Extracted archive did not contain an apache-jena-fuseki* directory');
        const extractedJar = path.join(extractDir, topLevel, 'fuseki-server.jar');
        if (!fs.existsSync(extractedJar)) throw new Error(`fuseki-server.jar not found inside ${topLevel}`);

        ensureDir(JARS_DIR);
        fs.copyFileSync(extractedJar, path.join(JARS_DIR, 'fuseki-server.jar'));

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

async function copyMongod() {
    console.log('\n[3/4] MongoDB binary');
    let anyMissing = false;
    for (const platform of ['win32', 'darwin', 'linux']) {
        const ext  = platform === 'win32' ? '.exe' : '';

        const isLinuxArm64 = platform === 'linux' && TARGET_ARCH === 'arm64';
        const cacheSubdir = platform === 'linux' ? (isLinuxArm64 ? 'linux-arm64' : 'linux-x64') : platform;
        const cachedSrc = path.join(REPO_ROOT, 'data', 'mongodb', 'bin', cacheSubdir, `mongod${ext}`);
        const dest = path.join(RESOURCES, 'mongodb', platform, `mongod${ext}`);
        let ok = copyIfExists(cachedSrc, dest, `mongodb/${platform}/mongod${ext}`);
        if (!ok && platform === 'win32') {
            console.log(`  → Not found locally — downloading MongoDB Community Server ${MONGODB_VERSION}…`);
            ok = await downloadMongodWin32(cachedSrc, dest);
        }
        if (!ok && platform === 'linux' && (platform === TARGET_PLATFORM || CROSS_BUILDING)) {
            if (isLinuxArm64) {
                console.log(`  → Not found locally — downloading MongoDB Community Server ${MONGODB_VERSION} (Ubuntu 22.04, arm64)…`);
                ok = await downloadMongodLinuxArm64Ubuntu2204(cachedSrc, dest);
            } else {
                console.log(`  → Not found locally — downloading MongoDB Community Server ${MONGODB_VERSION} (Ubuntu 22.04)…`);
                ok = await downloadMongodLinuxUbuntu2204(cachedSrc, dest);
            }
        }
        if (!ok) anyMissing = true;
    }
    if (anyMissing) {
        console.log('       macOS/Linux still require the manual download:');
        console.log('       Download: https://www.mongodb.com/try/download/community (zip/tgz)');
        console.log('       Extract mongod[.exe] → data/mongodb/bin/<platform>/');
    }
}

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

async function downloadMongodLinuxArm64Ubuntu2204(cachedSrc, dest) {
    const archivePath = path.join(RESOURCES, `mongodb-linux-aarch64-ubuntu2204-${MONGODB_VERSION}.tgz`);
    ensureDir(RESOURCES);

    try {
        await downloadFile(MONGODB_LINUX_ARM64_UBUNTU2204_URL, archivePath);

        const actualSha256 = crypto.createHash('sha256').update(fs.readFileSync(archivePath)).digest('hex');
        if (actualSha256 !== MONGODB_LINUX_ARM64_UBUNTU2204_SHA256) {
            throw new Error(
                `Checksum mismatch for mongodb-linux-aarch64-ubuntu2204-${MONGODB_VERSION}.tgz\n` +
                `       expected: ${MONGODB_LINUX_ARM64_UBUNTU2204_SHA256}\n       got:      ${actualSha256}`
            );
        }
        console.log('  ✓  Checksum verified');

        const extractDir = path.join(RESOURCES, `_mongodb-extract-linux-arm64-${MONGODB_VERSION}`);
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

        ensureDir(path.dirname(cachedSrc));
        fs.copyFileSync(extractedBin, cachedSrc);
        fs.chmodSync(cachedSrc, 0o755);
        console.log('  ✓  Downloaded and installed mongod (linux-arm64/ubuntu2204)');

        fs.rmSync(extractDir, { recursive: true, force: true });
        fs.rmSync(archivePath, { force: true });
        return true;
    } catch (err) {
        console.error(`  ✗  MongoDB (Linux arm64) download failed: ${err.message}`);
        fs.rmSync(archivePath, { force: true });
        return false;
    }
}

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

const CROSS_ARCH = TARGET_ARCH !== process.arch;
const SKIP_JLINK = CROSS_BUILDING || CROSS_ARCH;

const JRE_ARCH_MARKER = path.join(JRE_DIR, '.prepared-for');

async function bundleJre() {
    console.log('\n[4/5] JRE (bundled — no system Java required at runtime)');

    const javaBin = path.join(JRE_DIR, 'bin', TARGET_PLATFORM === 'win32' ? 'java.exe' : 'java');
    const expectedMarker = `${TARGET_PLATFORM}-${TARGET_ARCH}`;
    const actualMarker = fs.existsSync(JRE_ARCH_MARKER) ? fs.readFileSync(JRE_ARCH_MARKER, 'utf8').trim() : null;

    if (!SKIP_JLINK && fs.existsSync(javaBin) && actualMarker === expectedMarker) {
        const major = javaMajorVersion(javaBin);
        if (major === null) {
            console.warn('  ⚠  Existing JRE unreadable — re-creating');
            fs.rmSync(JRE_DIR, { recursive: true, force: true });
        } else if (major < REQUIRED_JRE_MAJOR) {
            console.warn(`  ⚠  Existing bundled JRE is Java ${major}, need >=${REQUIRED_JRE_MAJOR} — re-creating`);
            fs.rmSync(JRE_DIR, { recursive: true, force: true });
        } else {
            console.log(`  ✓  Bundled JRE already present: Java ${major}`);
            return;
        }
    } else if (SKIP_JLINK && fs.existsSync(javaBin) && actualMarker === expectedMarker) {

        console.log(`  ✓  Bundled JRE for ${expectedMarker} already present (existence check only — can't exec-verify a foreign binary)`);
        return;
    } else if (fs.existsSync(JRE_DIR) && actualMarker !== expectedMarker) {
        console.log(`  ℹ  Bundled JRE was prepared for ${actualMarker || 'an untracked build'}, need ${expectedMarker} — re-fetching`);
        fs.rmSync(JRE_DIR, { recursive: true, force: true });
    }

    if (!SKIP_JLINK && await tryJlink()) {
        ensureDir(JRE_DIR);
        fs.writeFileSync(JRE_ARCH_MARKER, expectedMarker);
        return;
    }

    await downloadTemurin();
    if (fs.existsSync(javaBin)) {
        fs.writeFileSync(JRE_ARCH_MARKER, expectedMarker);
    }
}

function findJdkHome(major) {
    for (const envName of [`JAVA${major}_HOME`, `JAVA_HOME_${major}`]) {
        if (process.env[envName] && fs.existsSync(process.env[envName])) {
            return process.env[envName];
        }
    }

    if (process.env.JAVA_HOME) {
        const bin = path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
        if (javaMajorVersion(bin) === major) return process.env.JAVA_HOME;
    }

    const candidateRoots = process.platform === 'win32'
        ? ['C:\\Program Files\\Eclipse Adoptium', 'C:\\Program Files\\Java', 'C:\\Program Files\\Microsoft',
           'C:\\Program Files\\Zulu', 'C:\\Program Files\\BellSoft', 'C:\\Program Files\\AdoptOpenJDK']
        : ['/usr/lib/jvm'];
    const namePattern = process.platform === 'win32'
        ? new RegExp(`^jdk-${major}`, 'i')
        : new RegExp(`^(java-${major}-openjdk|temurin-${major}-jdk|jdk-${major})`, 'i');
    for (const root of candidateRoots) {
        if (!fs.existsSync(root)) continue;
        const match = fs.readdirSync(root).find(name => namePattern.test(name));
        if (match) return path.join(root, match);
    }
    return null;
}

async function tryJlink() {
    const jdkHome = findJdkHome(REQUIRED_JRE_MAJOR);
    if (!jdkHome) {
        console.log(`  ℹ  No local JDK ${REQUIRED_JRE_MAJOR} found — will download pre-built JRE instead`);
        return false;
    }

    const jlinkBin = path.join(jdkHome, 'bin', process.platform === 'win32' ? 'jlink.exe' : 'jlink');
    if (!fs.existsSync(jlinkBin)) {
        console.log(`  ℹ  jlink not found under ${jdkHome} — will download pre-built JRE instead`);
        return false;
    }

    console.log(`  → Creating minimal JRE with jlink from ${jdkHome} (this takes ~30 s)…`);
    fs.rmSync(JRE_DIR, { recursive: true, force: true });
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

async function bundleSwrlJre() {
    const javaBin = path.join(JRE17_DIR, 'bin', TARGET_PLATFORM === 'win32' ? 'java.exe' : 'java');
    if (!CROSS_BUILDING && fs.existsSync(javaBin)) {
        const major = javaMajorVersion(javaBin);
        if (major === null) {
            console.warn('  ⚠  Existing SWRL JRE unreadable — re-creating');
            fs.rmSync(JRE17_DIR, { recursive: true, force: true });
        } else if (major !== 17) {
            console.warn(`  ⚠  Existing bundled SWRL JRE is Java ${major}, need exactly 17 — re-creating`);
            fs.rmSync(JRE17_DIR, { recursive: true, force: true });
        } else {
            console.log(`  ✓  Bundled SWRL JRE already present: Java ${major}`);
            return;
        }
    } else if (CROSS_BUILDING && fs.existsSync(javaBin)) {
        console.log(`  ✓  Bundled SWRL JRE for ${TARGET_PLATFORM} already present (existence check only — can't exec-verify a foreign binary)`);
        return;
    }

    if (!CROSS_BUILDING) {
        const jdk17Home = findJdkHome(17);
        if (jdk17Home) {
            const jlinkBin = path.join(jdk17Home, 'bin', process.platform === 'win32' ? 'jlink.exe' : 'jlink');
            const jdkJavaBin = path.join(jdk17Home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
            const jdkMajor = javaMajorVersion(jdkJavaBin);
            if (jdkMajor !== 17) {
                console.warn(`  ⚠  ${jdk17Home} is Java ${jdkMajor || '?'}, not 17 — ignoring`);
            } else if (fs.existsSync(jlinkBin)) {
                console.log(`  → Creating SWRL's dedicated JDK 17 JRE with jlink from ${jdk17Home} (this takes ~30 s)…`);
                fs.rmSync(JRE17_DIR, { recursive: true, force: true });
                try {
                    execSync(
                        `"${jlinkBin}" --add-modules ${JLINK_MODULES} --output "${JRE17_DIR}" --strip-debug --no-man-pages --no-header-files --compress=2`,
                        { stdio: 'inherit', timeout: 120_000 },
                    );
                    console.log('  ✓  SWRL JDK 17 JRE created via jlink (~60-80 MB)');
                    return;
                } catch (err) {
                    console.warn(`  ⚠  jlink failed for SWRL JRE: ${err.message}`);
                    if (fs.existsSync(JRE17_DIR)) fs.rmSync(JRE17_DIR, { recursive: true, force: true });
                }
            } else {
                console.warn(`  ⚠  jlink not found under ${jdk17Home}`);
            }
        } else {
            console.log('  ℹ  No local JDK 17 found — downloading a pre-built Temurin 17 JRE instead');
        }
    }

    await downloadTemurin17();
}

async function downloadTemurin17() {
    const platform = TARGET_PLATFORM;
    const url = (platform === 'linux' && TARGET_ARCH === 'arm64')
        ? TEMURIN_17_LINUX_ARM64_URL
        : TEMURIN_17_URLS[platform];
    if (!url) {
        console.error(`  ✗  No Temurin 17 URL configured for platform "${platform}" arch "${TARGET_ARCH}"`);
        console.error('     Add the URL to TEMURIN_17_URLS in prepare-resources.js');
        return;
    }

    console.log(`  → Downloading Temurin 17 JRE for ${platform}/${TARGET_ARCH}…`);
    console.log(`     ${url}`);

    ensureDir(JRE17_DIR);

    const archiveExt = platform === 'win32' ? '.zip' : '.tar.gz';
    const archivePath = path.join(RESOURCES, `temurin17-jre${archiveExt}`);

    try {
        await downloadFile(url, archivePath);
        console.log('  → Extracting JRE…');
        extractJreArchive(archivePath, JRE17_DIR, platform);
        fs.rmSync(archivePath, { force: true });
        console.log('  ✓  Temurin 17 JRE extracted to resources/backend/jre17/');
    } catch (err) {
        console.error(`  ✗  Download/extract failed: ${err.message}`);
        console.error('     Manual option: download from https://adoptium.net/temurin/releases/?version=17');
        console.error('     Extract to: electron-app/resources/backend/jre17/');
        console.error('     The jre17/ folder must contain bin/java (or bin/java.exe on Windows)');
    }
}

async function downloadTemurin() {
    const platform = TARGET_PLATFORM;
    const url = (platform === 'linux' && TARGET_ARCH === 'arm64')
        ? TEMURIN_LINUX_ARM64_URL
        : TEMURIN_URLS[platform];
    if (!url) {
        console.error(`  ✗  No Temurin URL configured for platform "${platform}" arch "${TARGET_ARCH}"`);
        console.error('     Add the URL to TEMURIN_URLS in prepare-resources.js');
        return;
    }

    console.log(`  → Downloading Temurin 21 JRE for ${platform}/${TARGET_ARCH}…`);
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

const DOWNLOAD_IDLE_TIMEOUT_MS = 60_000;
const DOWNLOAD_MAX_ATTEMPTS = 4;
const DOWNLOAD_RETRY_DELAY_MS = 5_000;

function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }

function downloadFileOnce(url, dest, redirectsLeft = 5) {
    return new Promise((resolve, reject) => {
        const file = fs.createWriteStream(dest);
        function follow(u, remaining) {
            const req = https.get(u, { family: 4, headers: { 'User-Agent': 'ontocode-build/1.0' } }, (res) => {
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
            req.setTimeout(DOWNLOAD_IDLE_TIMEOUT_MS, () => {
                req.destroy(new Error(`No data received for ${DOWNLOAD_IDLE_TIMEOUT_MS / 1000}s (stalled) — ${u}`));
            });
            req.on('error', reject);
        }
        follow(url, redirectsLeft);
    });
}

async function downloadFile(url, dest) {
    let lastErr;
    for (let attempt = 1; attempt <= DOWNLOAD_MAX_ATTEMPTS; attempt++) {
        try {
            await downloadFileOnce(url, dest);
            return;
        } catch (err) {
            lastErr = err;
            fs.rmSync(dest, { force: true });
            if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                console.warn(`\n     ⚠  Attempt ${attempt}/${DOWNLOAD_MAX_ATTEMPTS} failed (${err.message}) — retrying in ${DOWNLOAD_RETRY_DELAY_MS / 1000}s…`);
                await sleep(DOWNLOAD_RETRY_DELAY_MS);
            }
        }
    }
    throw lastErr;
}

function extractJreArchive(archivePath, targetDir, platform) {
    ensureDir(targetDir);
    if (platform === 'win32') {

        execSync(
            `powershell -NoProfile -Command "Expand-Archive -Path '${archivePath}' -DestinationPath '${targetDir}' -Force"`,
            { stdio: 'inherit', timeout: 120_000 },
        );

        flattenSingleSubdir(targetDir);
    } else {

        const tarArchivePath = archivePath.replace(/\\/g, '/');
        const tarTargetDir = targetDir.replace(/\\/g, '/');
        execSync(
            `tar --force-local -xzf "${tarArchivePath}" -C "${tarTargetDir}" --strip-components=1`,
            { stdio: 'inherit', timeout: 120_000 },
        );
    }
}

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

async function main() {
    console.log('\n=== OntoCode Studio — prepare-resources ===');
    if (CROSS_BUILDING) {
        console.log(`    Cross-building for TARGET_PLATFORM=${TARGET_PLATFORM} (host is ${process.platform})`);
    }
    ensureDir(JARS_DIR);

    copyOwlEditorJar();
    await copyFusekiJar();
    await copyMongod();
    await bundleJre();
    console.log('\n[5/5] SWRL JRE (dedicated JDK 17 — Drools/MVEL incompatible with JDK 21+)');
    await bundleSwrlJre();

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

    const swrlJreOk = fs.existsSync(path.join(JRE17_DIR, 'bin', TARGET_PLATFORM === 'win32' ? 'java.exe' : 'java'));
    console.log(`  ${swrlJreOk ? '✓' : '⚠'}  SWRL JRE (JDK 17)${swrlJreOk ? '' : ' — missing, SWRL reasoning will fail at runtime'}`);
    if (!allOk) {
        console.error('\nERROR: Missing required files. Fix the warnings above before running electron-builder.\n');
        process.exit(1);
    }
    console.log('\n✓  All required resources present — ready for: npm run dist\n');
}

main().catch(err => { console.error(err); process.exit(1); });
