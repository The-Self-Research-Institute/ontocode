

const fs            = require('fs');
const os            = require('os');
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

const JRE_CACHE_ROOT = path.join(RESOURCES, '.jre-cache');

const TARGET_PLATFORM = process.env.TARGET_PLATFORM || process.platform;
const CROSS_BUILDING = TARGET_PLATFORM !== process.platform;

const TARGET_ARCH = process.env.TARGET_ARCH || process.arch;

const FUSEKI_VERSION = '6.1.0';
const FUSEKI_SHA512 = '75457f45d14397876a41ed51abe7ae5d2f1e708dfe1315765f858158bc5c6813bc036ec1539ddc4dffd26201f5cc31fadec299ca5c3dc2548b723513ed31d326';
const FUSEKI_MIRROR_URL = `https://www.apache.org/dyn/mirrors/mirrors.cgi?action=download&filename=jena/binaries/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz`;
const FUSEKI_ARCHIVE_URL = `https://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-${FUSEKI_VERSION}.tar.gz`;
const TAR_LOCAL = process.platform === 'win32' ? '--force-local ' : '';

const POSTGRES_VERSION = '17.11.0';
const POSTGRES_MAJOR = '17';
const POSTGRES_MAVEN_BASE = 'https://repo1.maven.org/maven2/io/zonky/test/postgres';
const POSTGRES_BUILDS = {
    'win32-x64':    { artifact: 'windows-amd64',  sha256: '98040fae18dd9633ff95932125b0cecf0a45a1a9312e216e2a33ad03a31d4251' },
    'linux-x64':    { artifact: 'linux-amd64',    sha256: '0dd7b72b6f335b8ecfb355fa24c5781e8a93edd09880bb77eb52ebbf29b3e96d' },
    'linux-arm64':  { artifact: 'linux-arm64v8',  sha256: '8b042e0ea418b1927d95399207950da0734d27fe01e29503ade3bdc464ad4c2b' },
    'darwin-x64':   { artifact: 'darwin-amd64',   sha256: 'd464ff178e9860ba204662ac23fa547504b7fd392392ff2fb92e3fd73b5bdb64' },
    'darwin-arm64': { artifact: 'darwin-arm64v8', sha256: 'a1c2786acb0c398f9b2d76806fc52f5dc8b222cbc8e9383a9b9702084daaf3a5' },
};

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
        const out = execFileSync(javaBin, ['--version'], { encoding: 'utf8', timeout: 30000 });
        const m = out.match(/(?:openjdk|java)\s+(\d+)/i);
        return m ? parseInt(m[1], 10) : null;
    } catch (_) {
        return null;
    }
}

function ensureDir(d) { if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true }); }

function clearReadOnly(p) {
    const stat = fs.lstatSync(p);
    if (stat.isDirectory()) {
        fs.readdirSync(p).forEach((entry) => clearReadOnly(path.join(p, entry)));
    } else if (!stat.isSymbolicLink()) {
        fs.chmodSync(p, 0o666);
    }
}

function removeDir(dir) {
    if (!fs.existsSync(dir)) return;
    const options = { recursive: true, force: true, maxRetries: 10, retryDelay: 500 };
    try {
        fs.rmSync(dir, options);
    } catch (err) {
        if (err.code !== 'EPERM' && err.code !== 'EACCES') throw err;
        clearReadOnly(dir);
        fs.rmSync(dir, options);
    }
}

function jreCacheDir(marker) { return path.join(JRE_CACHE_ROOT, marker); }

function restoreJreFromCache(marker, destDir, javaBinRelPath) {
    const cacheDir = jreCacheDir(marker);
    const cachedJavaBin = path.join(cacheDir, javaBinRelPath);
    if (!fs.existsSync(cachedJavaBin)) return false;

    removeDir(destDir);
    fs.cpSync(cacheDir, destDir, { recursive: true });
    console.log(`  ✓  Restored ${marker} JRE from local cache (no download needed)`);
    return true;
}

function saveJreToCache(marker, srcDir) {
    try {
        const cacheDir = jreCacheDir(marker);
        removeDir(cacheDir);
        ensureDir(JRE_CACHE_ROOT);
        fs.cpSync(srcDir, cacheDir, { recursive: true });
    } catch (err) {
        console.warn(`  ⚠  Could not cache ${marker} JRE for reuse: ${err.message}`);
    }
}

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

function copyLgplLibs() {
    const srcDir = path.join(REPO_ROOT, 'ontology-desktop', 'target', 'lib');
    const destDir = path.join(RESOURCES, 'lib');
    removeDir(destDir);
    if (!fs.existsSync(srcDir)) {
        console.warn(`  ⚠  NOT FOUND: LGPL lib folder  (expected: ${srcDir})`);
        return;
    }
    ensureDir(destDir);
    for (const name of fs.readdirSync(srcDir).filter((f) => f.endsWith('.jar'))) {
        fs.copyFileSync(path.join(srcDir, name), path.join(destDir, name));
        console.log(`  ✓  Copied lib/${name}`);
    }
}

function copyDesktopJar() {
    console.log('\n[1/4] Desktop JAR (ontology-desktop)');
    const src = path.join(REPO_ROOT, 'ontology-desktop', 'target', 'ontology-desktop-1.0.0.jar');
    const dest = path.join(JARS_DIR, 'desktop.jar');
    const ok = copyIfExists(src, dest, 'desktop.jar');
    copyLgplLibs();
    if (!ok) {
        console.log('       Build:  mvn -pl ontology-desktop package -DskipTests');
        console.log('       Or:     node scripts/build-desktop.js --java');
    }
    return ok;
}

function copyOwlEditorJar() {
    copyDesktopJar();
}

function copySwrlJar() {
    const src = path.join(REPO_ROOT, 'ontology-swrl', 'target', 'ontology-swrl-1.0.0.jar');
    copyIfExists(src, path.join(JARS_DIR, 'swrl.jar'), 'swrl.jar');
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
        execSync(`tar ${TAR_LOCAL}-xzf "${tarArchivePath}" -C "${tarExtractDir}"`, { stdio: 'inherit', timeout: 120_000 });

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

async function copyPostgres() {
    console.log('\n[3/4] PostgreSQL binaries');
    const key = `${TARGET_PLATFORM}-${TARGET_ARCH}`;
    const source = POSTGRES_BUILDS[key];
    const destDir = path.join(RESOURCES, 'postgres', TARGET_PLATFORM);
    const exe = TARGET_PLATFORM === 'win32' ? 'postgres.exe' : 'postgres';
    if (!source) {
        console.error(`  ✗  No bundled PostgreSQL build for ${key}`);
        return false;
    }
    if (fs.existsSync(path.join(destDir, 'bin', exe)) && fs.existsSync(path.join(destDir, 'PG_MAJOR'))
        && fs.readFileSync(path.join(destDir, 'PG_MAJOR'), 'utf8').trim() === POSTGRES_MAJOR) {
        console.log(`  ✓  postgres/${TARGET_PLATFORM} already prepared`);
    } else {
        const ok = await downloadPostgres(source, destDir);
        if (!ok) return false;
    }
    await buildPostgresClusterTemplate(destDir);
    return true;
}

async function downloadPostgres(source, destDir) {
    const jarName = `embedded-postgres-binaries-${source.artifact}-${POSTGRES_VERSION}.jar`;
    const localJar = path.join(os.homedir(), '.m2', 'repository', 'io', 'zonky', 'test', 'postgres',
        `embedded-postgres-binaries-${source.artifact}`, POSTGRES_VERSION, jarName);
    const jarPath = path.join(RESOURCES, jarName);
    const extractDir = path.join(RESOURCES, `_postgres-extract-${TARGET_PLATFORM}`);
    ensureDir(RESOURCES);
    try {
        if (fs.existsSync(localJar)) {
            fs.copyFileSync(localJar, jarPath);
        } else {
            console.log(`  → Downloading PostgreSQL ${POSTGRES_VERSION} (${source.artifact}) from Maven Central…`);
            await downloadFile(`${POSTGRES_MAVEN_BASE}/embedded-postgres-binaries-${source.artifact}/${POSTGRES_VERSION}/${jarName}`, jarPath);
        }
        const actual = crypto.createHash('sha256').update(fs.readFileSync(jarPath)).digest('hex');
        if (actual !== source.sha256) {
            throw new Error(`Checksum mismatch for ${jarName}\n       expected: ${source.sha256}\n       got:      ${actual}`);
        }
        console.log('  ✓  Checksum verified');

        removeDir(extractDir);
        ensureDir(extractDir);
        if (process.platform === 'win32') {
            const zipCopy = path.join(extractDir, 'pg.zip');
            fs.copyFileSync(jarPath, zipCopy);
            execFileSync('powershell', ['-NoProfile', '-Command',
                `Expand-Archive -Path '${zipCopy}' -DestinationPath '${extractDir}' -Force`], { stdio: 'inherit', timeout: 120_000 });
        } else {
            execFileSync('unzip', ['-oq', jarPath, '-d', extractDir], { stdio: 'inherit', timeout: 120_000 });
        }
        const txz = fs.readdirSync(extractDir).find((n) => n.endsWith('.txz'));
        if (!txz) throw new Error('The PostgreSQL archive did not contain a .txz payload');

        removeDir(destDir);
        ensureDir(destDir);
        execFileSync('tar', ['-xJf', txz, '-C', path.relative(extractDir, destDir).replace(/\\/g, '/')],
            { stdio: 'inherit', timeout: 300_000, cwd: extractDir });
        fs.writeFileSync(path.join(destDir, 'PG_MAJOR'), POSTGRES_MAJOR, 'utf8');
        console.log(`  ✓  Installed PostgreSQL ${POSTGRES_VERSION} into postgres/${TARGET_PLATFORM}`);
        return true;
    } catch (err) {
        console.error(`  ✗  PostgreSQL preparation failed: ${err.message}`);
        return false;
    } finally {
        fs.rmSync(jarPath, { force: true });
        removeDir(extractDir);
    }
}

async function buildPostgresClusterTemplate(destDir) {
    const template = path.join(destDir, 'cluster-template');
    if (fs.existsSync(path.join(template, 'PG_VERSION'))) {
        console.log('  ✓  Empty database template already built');
        return;
    }
    if (CROSS_BUILDING || CROSS_ARCH) {
        console.log('  ⚠  Cross-building: no empty database template (the app creates it on first start, which takes longer)');
        return;
    }
    console.log('  → Building the empty database template (saves about 35 seconds on a first start)…');
    const runtime = require('../services/PostgresRuntime');
    removeDir(template);
    const password = crypto.randomBytes(24).toString('base64url');
    try {
        await runtime.buildCluster(destDir, template, password, { log: (l, m) => console.log('     ' + m) });
        fs.rmSync(path.join(template, 'postmaster.pid'), { force: true });
        console.log('  ✓  Empty database template ready');
    } catch (err) {
        removeDir(template);
        console.error('  ⚠  Could not build the database template (' + err.message + '); the app will create it on first start');
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
            removeDir(JRE_DIR);
        } else if (major < REQUIRED_JRE_MAJOR) {
            console.warn(`  ⚠  Existing bundled JRE is Java ${major}, need >=${REQUIRED_JRE_MAJOR} — re-creating`);
            removeDir(JRE_DIR);
        } else {
            console.log(`  ✓  Bundled JRE already present: Java ${major}`);
            return;
        }
    } else if (SKIP_JLINK && fs.existsSync(javaBin) && actualMarker === expectedMarker) {

        console.log(`  ✓  Bundled JRE for ${expectedMarker} already present (existence check only — can't exec-verify a foreign binary)`);
        return;
    } else if (fs.existsSync(JRE_DIR) && actualMarker !== expectedMarker) {
        console.log(`  ℹ  Bundled JRE was prepared for ${actualMarker || 'an untracked build'}, need ${expectedMarker}`);
    }

    const javaBinRelPath = path.relative(JRE_DIR, javaBin);
    if (restoreJreFromCache(expectedMarker, JRE_DIR, javaBinRelPath)) {
        fs.writeFileSync(JRE_ARCH_MARKER, expectedMarker);
        return;
    }
    removeDir(JRE_DIR);

    if (!SKIP_JLINK && await tryJlink()) {
        ensureDir(JRE_DIR);
        fs.writeFileSync(JRE_ARCH_MARKER, expectedMarker);
        return;
    }

    await downloadTemurin();
    if (fs.existsSync(javaBin)) {
        fs.writeFileSync(JRE_ARCH_MARKER, expectedMarker);
        saveJreToCache(expectedMarker, JRE_DIR);
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
    removeDir(JRE_DIR);
    try {
        execSync(
            `"${jlinkBin}" --add-modules ${JLINK_MODULES} --output "${JRE_DIR}" --strip-debug --no-man-pages --no-header-files --compress=2 --generate-cds-archive`,
            { stdio: 'inherit', timeout: 120_000 },
        );
        console.log('  ✓  Minimal JRE created via jlink (~60-80 MB)');
        return true;
    } catch (err) {
        console.warn(`  ⚠  jlink failed: ${err.message}`);
        if (fs.existsSync(JRE_DIR)) removeDir(JRE_DIR);
        return false;
    }
}

const JRE17_ARCH_MARKER = path.join(JRE17_DIR, '.prepared-for');

async function bundleSwrlJre() {
    const javaBin = path.join(JRE17_DIR, 'bin', TARGET_PLATFORM === 'win32' ? 'java.exe' : 'java');
    const expectedMarker = `${TARGET_PLATFORM}-${TARGET_ARCH}`;
    const actualMarker = fs.existsSync(JRE17_ARCH_MARKER) ? fs.readFileSync(JRE17_ARCH_MARKER, 'utf8').trim() : null;

    if (!SKIP_JLINK && fs.existsSync(javaBin) && actualMarker === expectedMarker) {
        const major = javaMajorVersion(javaBin);
        if (major === null) {
            console.warn('  ⚠  Existing SWRL JRE unreadable — re-creating');
            removeDir(JRE17_DIR);
        } else if (major !== 17) {
            console.warn(`  ⚠  Existing bundled SWRL JRE is Java ${major}, need exactly 17 — re-creating`);
            removeDir(JRE17_DIR);
        } else {
            console.log(`  ✓  Bundled SWRL JRE already present: Java ${major}`);
            return;
        }
    } else if (SKIP_JLINK && fs.existsSync(javaBin) && actualMarker === expectedMarker) {
        console.log(`  ✓  Bundled SWRL JRE for ${expectedMarker} already present (existence check only — can't exec-verify a foreign binary)`);
        return;
    } else if (fs.existsSync(JRE17_DIR) && actualMarker !== expectedMarker) {
        console.log(`  ℹ  Bundled SWRL JRE was prepared for ${actualMarker || 'an untracked build'}, need ${expectedMarker}`);
    }

    const javaBinRelPath = path.relative(JRE17_DIR, javaBin);
    if (restoreJreFromCache(`swrl-${expectedMarker}`, JRE17_DIR, javaBinRelPath)) {
        fs.writeFileSync(JRE17_ARCH_MARKER, expectedMarker);
        return;
    }
    removeDir(JRE17_DIR);

    if (!SKIP_JLINK) {
        const jdk17Home = findJdkHome(17);
        if (jdk17Home) {
            const jlinkBin = path.join(jdk17Home, 'bin', process.platform === 'win32' ? 'jlink.exe' : 'jlink');
            const jdkJavaBin = path.join(jdk17Home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
            const jdkMajor = javaMajorVersion(jdkJavaBin);
            if (jdkMajor !== 17) {
                console.warn(`  ⚠  ${jdk17Home} is Java ${jdkMajor || '?'}, not 17 — ignoring`);
            } else if (fs.existsSync(jlinkBin)) {
                console.log(`  → Creating SWRL's dedicated JDK 17 JRE with jlink from ${jdk17Home} (this takes ~30 s)…`);
                try {
                    execSync(
                        `"${jlinkBin}" --add-modules ${JLINK_MODULES} --output "${JRE17_DIR}" --strip-debug --no-man-pages --no-header-files --compress=2 --generate-cds-archive`,
                        { stdio: 'inherit', timeout: 120_000 },
                    );
                    console.log('  ✓  SWRL JDK 17 JRE created via jlink (~60-80 MB)');
                    fs.writeFileSync(JRE17_ARCH_MARKER, expectedMarker);
                    return;
                } catch (err) {
                    console.warn(`  ⚠  jlink failed for SWRL JRE: ${err.message}`);
                    if (fs.existsSync(JRE17_DIR)) removeDir(JRE17_DIR);
                }
            } else {
                console.warn(`  ⚠  jlink not found under ${jdk17Home}`);
            }
        } else {
            console.log('  ℹ  No local JDK 17 found — downloading a pre-built Temurin 17 JRE instead');
        }
    }

    await downloadTemurin17();
    if (fs.existsSync(javaBin)) {
        fs.writeFileSync(JRE17_ARCH_MARKER, expectedMarker);
        saveJreToCache(`swrl-${expectedMarker}`, JRE17_DIR);
    }
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
            `tar ${TAR_LOCAL}-xzf "${tarArchivePath}" -C "${tarTargetDir}" --strip-components=1`,
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

function generateJreBaseCdsArchives() {
    console.log('\n[7/7] JRE base class-data archives (an installed Store app cannot create them)');
    if (CROSS_BUILDING || CROSS_ARCH) {
        console.log('  -  Skipped: the target JRE cannot run on this host');
        return;
    }
    const exe = process.platform === 'win32' ? 'java.exe' : 'java';
    for (const dir of ['jre', 'jre17']) {
        const javaBin = path.join(RESOURCES, dir, 'bin', exe);
        if (!fs.existsSync(javaBin)) continue;
        try {
            execFileSync(javaBin, ['-Xshare:dump'], { stdio: 'ignore', timeout: 120_000 });
            const archive = path.join(RESOURCES, dir, 'bin', 'server', 'classes.jsa');
            console.log(fs.existsSync(archive) ? `  ✓  ${dir}: classes.jsa generated` : `  ⚠  ${dir}: dump ran but no classes.jsa found`);
        } catch (err) {
            console.warn(`  ⚠  ${dir}: could not generate classes.jsa (${err.message})`);
        }
    }
}

async function main() {
    console.log('\n=== OntoCode Studio — prepare-resources ===');
    if (CROSS_BUILDING) {
        console.log(`    Cross-building for TARGET_PLATFORM=${TARGET_PLATFORM} (host is ${process.platform})`);
    }
    ensureDir(JARS_DIR);

    copyOwlEditorJar();
    await copyFusekiJar();
    await copyPostgres();
    await bundleJre();
    console.log('\n[5/5] SWRL JRE (dedicated JDK 17 — Drools/MVEL incompatible with JDK 21+)');
    await bundleSwrlJre();
    copySwrlJar();
    generateJreBaseCdsArchives();

    if (!requiredResourcesPresent()) {
        console.error('\nERROR: Missing required files. Fix the warnings above before running electron-builder.\n');
        process.exit(1);
    }
    console.log('\n✓  All required resources present — ready for: npm run dist\n');
}

function requiredResourcesPresent() {
    console.log('\n=== Summary ===');
    const checks = {
        'desktop.jar':       path.join(JARS_DIR, 'desktop.jar'),
        'fuseki-server.jar': path.join(JARS_DIR, 'fuseki-server.jar'),
        'postgres':          path.join(RESOURCES, 'postgres', TARGET_PLATFORM, 'bin', `postgres${TARGET_PLATFORM === 'win32' ? '.exe' : ''}`),
        'JRE':               path.join(JRE_DIR, 'bin', TARGET_PLATFORM === 'win32' ? 'java.exe' : 'java'),
    };
    let allOk = true;
    for (const [label, p] of Object.entries(checks)) {
        const ok = fs.existsSync(p);
        console.log(`  ${ok ? '✓' : '✗'}  ${label}`);
        if (!ok) allOk = false;
    }
    const templateOk = fs.existsSync(path.join(RESOURCES, 'postgres', TARGET_PLATFORM, 'cluster-template', 'PG_VERSION'));
    console.log(`  ${templateOk ? '✓' : '⚠'}  empty database template${templateOk ? '' : ' — missing, the first start will take about 35 seconds longer'}`);

    const swrlJreOk = fs.existsSync(path.join(JRE17_DIR, 'bin', TARGET_PLATFORM === 'win32' ? 'java.exe' : 'java'));
    console.log(`  ${swrlJreOk ? '✓' : '⚠'}  SWRL JRE (JDK 17)${swrlJreOk ? '' : ' — missing, SWRL reasoning will fail at runtime'}`);
    const swrlJarOk = fs.existsSync(path.join(JARS_DIR, 'swrl.jar'));
    console.log(`  ${swrlJarOk ? '✓' : '⚠'}  swrl.jar${swrlJarOk ? '' : ' — missing, build it with: mvn package -pl ontology-swrl -DskipTests (JDK 17)'}`);
    return allOk;
}

if (require.main === module) {
    main().catch(err => { console.error(err); process.exit(1); });
}

module.exports = { copyPostgres, POSTGRES_BUILDS };
