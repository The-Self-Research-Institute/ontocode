

const { app } = require('electron');
const { spawn } = require('child_process');
const path = require('path');
const fs   = require('fs');
const http = require('http');
const os   = require('os');
const crypto = require('crypto');

function jvmHeaps() {
    const totalGb = os.totalmem() / (1024 ** 3);
    const desktopGb = Math.min(Math.max(Math.floor(totalGb * 0.40), 2), 12);
    const fusekiGb  = Math.min(Math.max(Math.floor(totalGb * 0.28), 2), 8);
    return { desktopXmx: `${desktopGb}g`, fusekiXmx: `${fusekiGb}g`, totalGb: Math.round(totalGb) };
}

const RESOURCES_DIR = app.isPackaged
    ? path.join(process.resourcesPath, 'backend')
    : path.join(__dirname, '..', 'resources', 'backend');

const DATA_DIR        = path.join(app.getPath('userData'), 'data');
const MONGO_DATA_DIR  = path.join(DATA_DIR, 'mongo');
const FUSEKI_DATA_DIR = path.join(DATA_DIR, 'fuseki');
const OWL_DATA_DIR    = path.join(DATA_DIR, 'ontologies');
const LOGS_DIR        = path.join(app.getPath('userData'), 'logs');
const FUSEKI_BASE_DIR = path.join(app.getPath('userData'), 'fuseki-base');

const WINDOWS_JVM_FLAGS = process.platform === 'win32'
    ? [`-Djdk.net.unixdomain.tmpdir=${path.join(DATA_DIR, 'unix-sockets-disabled')}`]
    : [];

const helperProcesses = new Set();
let startupCancelled = false;

function assertNotCancelled() {
    if (startupCancelled) throw new Error('Startup cancelled');
}

const CDS_TRAINING_TIMEOUT_MS = 420000;
const CDS_TRAINING_DELAY_MS = 45000;
const CDS_TRAINING_MIN_FREE_BYTES = (Number(process.env.ONTOCODE_CDS_MIN_FREE_MB) || 1200) * 1024 * 1024;
const CDS_TRAINING_RETRY_MS = 180000;
const CDS_TRAINING_MAX_DEFERRALS = 5;
const CDS_TRAINING_MAX_FAILURES = 3;
const CDS_LOCK_STALE_MS = 15 * 60 * 1000;
const pendingTraining = [];
let trainingTimer = null;
let trainingActive = false;
const STARTUP_HEALTH_TIMEOUT_MS = 300000;

const DEFAULT_PORTS = {
    mongo:   27117,
    fuseki:  13030,
    desktop: 18083,
    swrl:    18084,
    proxy:   18085,
};

let MONGO_PORT   = DEFAULT_PORTS.mongo;
let FUSEKI_PORT  = DEFAULT_PORTS.fuseki;
let DESKTOP_PORT = DEFAULT_PORTS.desktop;
let SWRL_PORT    = DEFAULT_PORTS.swrl;

let mongoProcess   = null;

const LAZY_FUSEKI = process.env.ONTOCODE_LAZY_FUSEKI !== '0';

let fusekiProcess  = null;
let fusekiStartPromise = null;
let desktopProcess = null;

const LAZY_SWRL = process.env.ONTOCODE_LAZY_SWRL !== '0';
let swrlProcess    = null;
let swrlStartPromise = null;
let _logCallback   = null;

function isPortFree(port) {
    return new Promise((resolve) => {
        const net = require('net');
        const srv = net.createServer();
        srv.once('error', () => resolve(false));
        srv.once('listening', () => { srv.close(); resolve(true); });
        srv.listen(port, '127.0.0.1');
    });
}

async function findFreePort(preferred, label) {
    for (let p = preferred; p < preferred + 20; p++) {
        if (await isPortFree(p)) {
            if (p !== preferred) log('warn', `${label}: port ${preferred} in use, using ${p} instead`);
            return p;
        }
    }
    throw new Error(`${label}: no free port found near ${preferred}`);
}

async function resolveAllPorts() {
    log('info', 'Checking port availability…');
    MONGO_PORT   = await findFreePort(DEFAULT_PORTS.mongo,   'MongoDB');
    FUSEKI_PORT  = await findFreePort(DEFAULT_PORTS.fuseki,  'Fuseki');
    DESKTOP_PORT = await findFreePort(DEFAULT_PORTS.desktop, 'Desktop');
    SWRL_PORT    = await findFreePort(DEFAULT_PORTS.swrl,    'SWRL');
    log('info', `Ports → MongoDB:${MONGO_PORT}  Fuseki:${FUSEKI_PORT}  Desktop:${DESKTOP_PORT}  SWRL:${SWRL_PORT}`);
}

const DESKTOP_LAUNCH_KEY = crypto.randomBytes(32).toString('hex');

module.exports = {
    get DESKTOP_LAUNCH_KEY() { return DESKTOP_LAUNCH_KEY; },
    get MONGO_PORT()   { return MONGO_PORT; },
    get FUSEKI_PORT()  { return FUSEKI_PORT; },
    get DESKTOP_PORT() { return DESKTOP_PORT; },
    get SWRL_PORT()    { return SWRL_PORT; },
    get EDITOR_PORT()  { return DESKTOP_PORT; },
    get AUTH_PORT()    { return DESKTOP_PORT; },

    onLog(callback) { _logCallback = callback; },

    async startAll() {
        startupCancelled = false;
        ensureDirs();
        validateBackendBundles();
        await resolveAllPorts();
        await startMongo();
        assertNotCancelled();
        if (!LAZY_FUSEKI) {
            await startFuseki();
        } else {
            log('info', 'Fuseki deferred (OWLAPI-first desktop — starts when SPARQL/graph needs it)');
        }
        if (LAZY_SWRL) {
            await startDesktop();
            log('info', 'SWRL reasoner deferred — starts on the first /api/swrl/** request');
        } else {

            try {
                await Promise.all([startDesktop(), startSwrl()]);   // SWRL optional — skipped silently if swrl.jar absent
            } catch (err) {

                await Promise.all([
                    stopProcess(desktopProcess, 'Desktop', 4000),
                    stopProcess(swrlProcess, 'SWRL', 4000),
                ]);
                throw err;
            }
        }
    },

    async ensureFuseki() {

        if (fusekiProcess && !fusekiProcess.killed && fusekiProcess.exitCode === null) {
            return { running: true, port: FUSEKI_PORT };
        }
        if (!fusekiStartPromise) {
            fusekiStartPromise = startFuseki().then(() => {
                fusekiStartPromise = null;
                return { running: true, port: FUSEKI_PORT };
            }).catch((err) => {
                fusekiStartPromise = null;
                throw err;
            });
        }
        return fusekiStartPromise;
    },

    async ensureSwrl() {

        if (swrlProcess && !swrlProcess.killed && swrlProcess.exitCode === null) {
            return { running: true, port: SWRL_PORT };
        }
        if (!swrlStartPromise) {
            swrlStartPromise = startSwrl().then(() => {
                swrlStartPromise = null;

                const running = Boolean(swrlProcess && !swrlProcess.killed && swrlProcess.exitCode === null);
                return { running, port: SWRL_PORT };
            }).catch((err) => {
                swrlStartPromise = null;
                throw err;
            });
        }
        return swrlStartPromise;
    },

    async stopAll() {
        startupCancelled = true;
        clearTimeout(trainingTimer);
        trainingTimer = null;
        pendingTraining.length = 0;
        helperProcesses.forEach((p) => { try { p.kill('SIGKILL'); } catch (_) {} });
        helperProcesses.clear();
        await stopProcess(swrlProcess,    'SWRL',    4000);
        await stopProcess(desktopProcess, 'Desktop', 10000);
        await stopProcess(fusekiProcess,  'Fuseki',  4000);
        await stopProcess(mongoProcess,   'MongoDB', 4000);
        swrlProcess    = null;
        desktopProcess = null;
        fusekiProcess  = null;
        mongoProcess   = null;
    },

    status() {

        const isRunning = (p) => Boolean(p && !p.killed && p.exitCode === null);
        return {
            mongo:   isRunning(mongoProcess),
            fuseki:  isRunning(fusekiProcess),
            desktop: isRunning(desktopProcess),
            swrl:    isRunning(swrlProcess),
        };
    },
};

function ensureDirs() {
    [MONGO_DATA_DIR, FUSEKI_DATA_DIR, OWL_DATA_DIR, LOGS_DIR, FUSEKI_BASE_DIR].forEach(d => {
        if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true });
    });
}

function log(level, msg) {
    console.log(`[${level.toUpperCase()}] ${msg}`);
    if (_logCallback) _logCallback(level, msg);
}

function mongoBin() {
    const platform = process.platform;
    const dir = path.join(RESOURCES_DIR, 'mongodb', platform === 'win32' ? 'win32' : platform);
    const name = platform === 'win32' ? 'mongod.exe' : 'mongod';
    const p = path.join(dir, name);
    return fs.existsSync(p) ? p : 'mongod';
}

function javaBin() {
    const exe = process.platform === 'win32' ? 'java.exe' : 'java';
    const bundled = path.join(RESOURCES_DIR, 'jre', 'bin', exe);
    if (fs.existsSync(bundled)) return bundled;
    if (process.env.JAVA_HOME) {
        const jh = path.join(process.env.JAVA_HOME, 'bin', exe);
        if (fs.existsSync(jh)) return jh;
    }
    return 'java';
}

function swrlJavaBin() {
    const exe = process.platform === 'win32' ? 'java.exe' : 'java';
    const bundled17 = path.join(RESOURCES_DIR, 'jre17', 'bin', exe);
    if (fs.existsSync(bundled17)) return bundled17;
    if (process.env.JAVA17_HOME) {
        const jh = path.join(process.env.JAVA17_HOME, 'bin', exe);
        if (fs.existsSync(jh)) return jh;
    }

    return javaBin();
}

function requiredJarPath(name) {
    return path.join(RESOURCES_DIR, 'jars', name);
}

function validateBackendBundles() {
    const required = ['desktop.jar', 'fuseki-server.jar'];
    const missing = required.filter((name) => !fs.existsSync(requiredJarPath(name)));
    if (missing.length === 0) return;

    const lines = [
        'OntoCode Desktop is missing backend JAR files:',
        ...missing.map((name) => `  • ${path.join(RESOURCES_DIR, 'jars', name)}`),
        '',
        'Your packaged app was built before desktop.jar was copied into resources.',
        'Fix (from electron-app):',
        '  node scripts/build-desktop.js --java --resources',
        '  node scripts/sync-packaged-backend.js',
        'Or rebuild: npm run dist:win',
    ];
    throw new Error(lines.join('\n'));
}

// Mongo exit code 62 ("NeedDowngrade") means the on-disk data files were written by a
// different, incompatible MongoDB version than the one we're bundling now — e.g. a user
// updates the app and our bundled mongod version changed since their last local database
// was created. This is only ever a problem for someone already on disk with the OLD
// format; it must never trigger for the common case (same version, or a brand-new
// install), so we only react to it AFTER a real start attempt fails with exactly this code.
const MONGO_EXIT_NEED_DOWNGRADE = 62;

const STATUS_DLL_NOT_FOUND = 0xC0000135;

function isDllNotFound(code) {
    return typeof code === 'number' && (code >>> 0) === STATUS_DLL_NOT_FOUND;
}

async function attemptStartMongo(dataDir) {
    const logFile = path.join(LOGS_DIR, 'mongo.log');
    const proc = spawnService('MongoDB', mongoBin(), [
        '--dbpath', dataDir,
        '--port',   String(MONGO_PORT),
        '--logpath', logFile,
        '--logappend',
        '--bind_ip', '127.0.0.1',
    ], {});

    let onEarlyExit, onSpawnError, exitCode = null;
    const earlyDeath = new Promise((_, reject) => {
        onEarlyExit = (code) => {
            exitCode = code;
            const reason = isDllNotFound(code) ? ' (a required Windows library is missing next to mongod.exe)' : '';
            reject(new Error(`MongoDB exited with code ${code} before becoming ready${reason}`));
        };
        onSpawnError = (err) => reject(new Error(`MongoDB failed to start: ${err.message}`));
        proc.once('exit', onEarlyExit);
        proc.once('error', onSpawnError);
    });
    try {
        await Promise.race([
            waitForTcp('127.0.0.1', MONGO_PORT, 30000, 'MongoDB'),
            earlyDeath,
        ]);
        proc.removeListener('exit', onEarlyExit);
        proc.removeListener('error', onSpawnError);
        return proc;
    } catch (err) {
        proc.removeListener('exit', onEarlyExit);
        proc.removeListener('error', onSpawnError);
        await stopProcess(proc, 'MongoDB', 4000);
        err.mongoExitCode = exitCode;
        throw err;
    }
}

async function startMongo() {
    log('info', 'Starting MongoDB…');

    const mongoLock = path.join(MONGO_DATA_DIR, 'mongod.lock');
    try {
        if (fs.existsSync(mongoLock) && fs.readFileSync(mongoLock, 'utf8').trim()) {
            fs.unlinkSync(mongoLock);
            log('info', `Removed stale MongoDB lock: ${mongoLock}`);
        }
    } catch (_) {}

    try {
        mongoProcess = await attemptStartMongo(MONGO_DATA_DIR);
    } catch (err) {
        if (err.mongoExitCode !== MONGO_EXIT_NEED_DOWNGRADE) throw err;

        // Existing data is from an incompatible MongoDB version. Back it up (never delete)
        // and retry once against a fresh directory — this is the ONLY path that ever
        // touches an existing user's data dir, and only after a real failure confirms it's
        // actually needed.
        const backupDir = `${MONGO_DATA_DIR}.incompatible-${Date.now()}`;
        log('warn', `MongoDB data at ${MONGO_DATA_DIR} is from an incompatible version (exit 62). `
            + `Backing up to ${backupDir} and starting fresh — original data is preserved, not deleted.`);
        fs.renameSync(MONGO_DATA_DIR, backupDir);
        fs.mkdirSync(MONGO_DATA_DIR, { recursive: true });
        mongoProcess = await attemptStartMongo(MONGO_DATA_DIR);
        log('warn', `Recovered from incompatible MongoDB data. Previous data backed up at: ${backupDir}`);
    }
    log('ok', `MongoDB ready on port ${MONGO_PORT}`);
}

async function startFuseki() {
    log('info', 'Starting Apache Fuseki…');

    const lockPaths = [
        path.join(FUSEKI_DATA_DIR, 'tdb.lock'),
        path.join(FUSEKI_DATA_DIR, 'Data-0001', 'tdb.lock'),
    ];
    const databasesDir = path.join(FUSEKI_BASE_DIR, 'databases');
    try {
        if (fs.existsSync(databasesDir)) {
            fs.readdirSync(databasesDir, { withFileTypes: true })
                .filter(entry => entry.isDirectory())
                .forEach(entry => {
                    const projectDbDir = path.join(databasesDir, entry.name);
                    lockPaths.push(
                        path.join(projectDbDir, 'tdb.lock'),
                        path.join(projectDbDir, 'Data-0001', 'tdb.lock'),
                    );
                });
        }
    } catch (e) {
        log('warn', `Could not enumerate per-project Fuseki databases for lock cleanup: ${e.message}`);
    }
    let removedLocks = 0;
    lockPaths.forEach(p => {
        try { if (fs.existsSync(p)) { fs.unlinkSync(p); removedLocks++; log('info', `Removed stale lock: ${p}`); } }
        catch (_) {}
    });
    if (removedLocks > 0) {
        log('info', `Cleared ${removedLocks} stale TDB2 lock file(s) before starting Fuseki`);
    }

    const jar     = path.join(RESOURCES_DIR, 'jars', 'fuseki-server.jar');
    const logFile = path.join(LOGS_DIR, 'fuseki.log');

    const configFile = path.join(FUSEKI_BASE_DIR, 'ontocode-config.ttl');
    const dataPath = FUSEKI_DATA_DIR.replace(/\\/g, '/');
    const fusekiConfig = `
@prefix fuseki:  <http://jena.apache.org/fuseki#> .
@prefix rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix tdb2:    <http://jena.apache.org/2016/tdb#> .
@prefix ja:      <http://jena.hpl.hp.com/2005/11/Assembler#> .

[] rdf:type fuseki:Server ;
   fuseki:services ( <#service> ) .

<#service> rdf:type fuseki:Service ;
    fuseki:name "ontocode" ;
    fuseki:endpoint [ fuseki:operation fuseki:query ;
                      fuseki:name "query" ;
                      ja:context [ ja:cxtName "arq:queryTimeout" ; ja:cxtValue "120000,120000" ] ] ;
    fuseki:endpoint [ fuseki:operation fuseki:update ; fuseki:name "update" ] ;
    fuseki:endpoint [ fuseki:operation fuseki:gsp-r ;  fuseki:name "get"    ] ;
    fuseki:endpoint [ fuseki:operation fuseki:gsp-rw ; fuseki:name "data"   ] ;
    fuseki:dataset <#dataset> ;
    .

<#dataset> rdf:type tdb2:DatasetTDB2 ;
    tdb2:location "${dataPath}" ;
    .
`.trim();
    fs.writeFileSync(configFile, fusekiConfig, 'utf8');
    log('info', `Fuseki config written to ${configFile}`);

    const fusekiHeaps = jvmHeaps();
    log('info', `[Fuseki] JVM heap: ${fusekiHeaps.fusekiXmx} (system RAM: ${fusekiHeaps.totalGb} GB)`);

    const args = [
        `-Xmx${fusekiHeaps.fusekiXmx}`,
        '-Xms256m',
        ...WINDOWS_JVM_FLAGS,
        '-jar', jar,
        '--port', String(FUSEKI_PORT),
        '--localhost',
        '--config', configFile,
    ];

    fusekiProcess = spawnService('Fuseki', javaBin(), args, {
        FUSEKI_HOME: path.join(RESOURCES_DIR, 'fuseki'),
        FUSEKI_BASE: FUSEKI_BASE_DIR,
    }, logFile);

    let onEarlyExit, onSpawnError;
    const earlyDeath = new Promise((_, reject) => {
        onEarlyExit = (code) => reject(new Error(`Fuseki exited with code ${code} before becoming ready`));
        onSpawnError = (err) => reject(new Error(`Fuseki failed to start: ${err.message}`));
        fusekiProcess.once('exit', onEarlyExit);
        fusekiProcess.once('error', onSpawnError);
    });
    try {
        await Promise.race([
            waitForHttp(`http://127.0.0.1:${FUSEKI_PORT}/$/ping`, 45000, 'Fuseki'),
            earlyDeath,
        ]);
    } catch (err) {

        fusekiProcess.removeListener('exit', onEarlyExit);
        fusekiProcess.removeListener('error', onSpawnError);

        await stopProcess(fusekiProcess, 'Fuseki', 4000);
        fusekiProcess = null;
        throw err;
    } finally {

        if (fusekiProcess) {
            fusekiProcess.removeListener('exit', onEarlyExit);
            fusekiProcess.removeListener('error', onSpawnError);
        }
    }
    log('ok', `Fuseki ready on port ${FUSEKI_PORT}`);
}

async function ensureBaseCdsArchive(javaBinPath) {
    const jreHome     = path.dirname(path.dirname(javaBinPath));

    const baseArchive = path.join(jreHome, 'bin', 'server', 'classes.jsa');
    if (fs.existsSync(baseArchive)) return;
    log('info', '[CDS] Generating base archive for bundled JRE (one-time)…');
    try {
        await runToCompletion(javaBinPath, ['-Xshare:dump'], {});
    } catch (err) {
        log('warn', `[CDS] Failed to generate base archive: ${err.message}`);
    }
}

// The JVM creates the CDS archive (*.jsa) read-only after -XX:ArchiveClassesAtExit
// completes. On Windows, fs.rmSync can't delete a read-only file even with force:true
// (that flag only swallows ENOENT, not EPERM) — unlike POSIX unlink, which only cares
// about the containing directory's permissions. Clear the attribute recursively first.
function removeCdsDir(dir) {
    try {
        fs.rmSync(dir, { recursive: true, force: true });
        return;
    } catch (err) {
        if (err.code !== 'EPERM' && err.code !== 'EACCES') throw err;
    }
    const clearReadOnly = (p) => {
        const stat = fs.statSync(p);
        if (stat.isDirectory()) {
            fs.readdirSync(p).forEach((entry) => clearReadOnly(path.join(p, entry)));
        } else {
            fs.chmodSync(p, 0o666);
        }
    };
    clearReadOnly(dir);
    fs.rmSync(dir, { recursive: true, force: true });
}

function discardPartialArchive(archiveFile) {
    try {
        fs.chmodSync(archiveFile, 0o666);
    } catch (_) {}
    try {
        fs.rmSync(archiveFile, { force: true });
    } catch (_) {}
}

async function prepareCds(name, originalJar, cdsDir, springArgs, env, javaBinPath) {
    const extractedJar = path.join(cdsDir, 'extracted', path.basename(originalJar));
    const archiveFile  = path.join(cdsDir, `${name.toLowerCase()}.jsa`);
    const markerFile   = path.join(cdsDir, '.java-bin');

    const jarStat = fs.statSync(originalJar);
    const jarFingerprint = `${jarStat.mtimeMs}:${jarStat.size}`;
    let recorded = null;
    if (fs.existsSync(markerFile)) {
        try { recorded = JSON.parse(fs.readFileSync(markerFile, 'utf8')); } catch { /* treat as stale below */ }
    }
    const isFresh = recorded && recorded.javaBin === javaBinPath && recorded.jarFingerprint === jarFingerprint;
    if (fs.existsSync(cdsDir) && !isFresh) {
        log('info', `[${name}] CDS cache missing/stale (JVM or jar changed) — regenerating`);
        removeCdsDir(cdsDir);
    }

    await ensureBaseCdsArchive(javaBinPath);

    if (fs.existsSync(archiveFile) && fs.existsSync(extractedJar)) {
        const leftover = path.join(cdsDir, 'scratch');
        if (fs.existsSync(leftover)) removeScratch(leftover);
        return { launchJar: extractedJar, cdsFlags: [`-XX:SharedArchiveFile=${archiveFile}`] };
    }

    queueCdsTraining({ name, originalJar, cdsDir, springArgs, env, javaBinPath, jarFingerprint });
    return { launchJar: originalJar, cdsFlags: [] };
}

function queueCdsTraining(job) {
    if (pendingTraining.some((queued) => queued.name === job.name)) return;
    pendingTraining.push(job);
    if (trainingTimer || trainingActive) return;
    trainingTimer = setTimeout(runQueuedTraining, CDS_TRAINING_DELAY_MS);
    trainingTimer.unref();
}

async function runQueuedTraining() {
    trainingTimer = null;
    trainingActive = true;
    try {
        while (pendingTraining.length && !startupCancelled) {
            const job = pendingTraining.shift();
            const outcome = await trainCdsInBackground(job);
            if (outcome === 'deferred' && !startupCancelled) {
                job.deferrals = (job.deferrals || 0) + 1;
                if (job.deferrals < CDS_TRAINING_MAX_DEFERRALS) {
                    pendingTraining.push(job);
                    trainingTimer = setTimeout(runQueuedTraining, CDS_TRAINING_RETRY_MS);
                    trainingTimer.unref();
                    return;
                }
                log('info', `[${job.name}] Startup cache postponed to a later launch (memory stayed low)`);
            }
        }
    } finally {
        trainingActive = false;
    }
}

function readCount(file) {
    try { return parseInt(fs.readFileSync(file, 'utf8'), 10) || 0; } catch (_) { return 0; }
}

function acquireLock(lockFile) {
    for (let attempt = 0; attempt < 2; attempt++) {
        try {
            fs.closeSync(fs.openSync(lockFile, 'wx'));
            return true;
        } catch (err) {
            if (err.code !== 'EEXIST') return false;
            try {
                if (Date.now() - fs.statSync(lockFile).mtimeMs < CDS_LOCK_STALE_MS) return false;
                fs.rmSync(lockFile, { force: true });
            } catch (_) { return false; }
        }
    }
    return false;
}

async function startScratchMongo(scratchDir) {
    const port = await findFreePort(27300, 'CDS training MongoDB');
    const dbPath = path.join(scratchDir, 'mongo');
    fs.mkdirSync(dbPath, { recursive: true });
    const proc = spawn(mongoBin(), [
        '--dbpath', dbPath, '--port', String(port), '--bind_ip', '127.0.0.1',
        '--logpath', path.join(scratchDir, 'mongo.log'),
    ], { stdio: 'ignore', windowsHide: true });
    helperProcesses.add(proc);
    proc.once('exit', () => helperProcesses.delete(proc));
    await waitForTcp('127.0.0.1', port, 30000, 'scratch MongoDB');
    return { proc, port };
}

async function trainCdsInBackground({ name, originalJar, cdsDir, springArgs, env, javaBinPath, jarFingerprint }) {
    const failuresFile = path.join(cdsDir, '.train-failures');
    const extractedDir = path.join(cdsDir, 'extracted');
    const extractedJar = path.join(extractedDir, path.basename(originalJar));
    const archiveFile  = path.join(cdsDir, `${name.toLowerCase()}.jsa`);
    const tempArchive  = `${archiveFile}.tmp`;
    const scratchDir   = path.join(cdsDir, 'scratch');
    const lockFile     = path.join(cdsDir, 'training.lock');

    const failures = readCount(failuresFile);
    if (failures >= CDS_TRAINING_MAX_FAILURES) {
        log('info', `[${name}] Startup cache disabled after ${failures} failed attempts`);
        return;
    }
    if (os.freemem() < CDS_TRAINING_MIN_FREE_BYTES) {
        log('info', `[${name}] Startup cache waiting for more free memory`);
        return 'deferred';
    }
    fs.mkdirSync(cdsDir, { recursive: true });
    if (!acquireLock(lockFile)) {
        log('info', `[${name}] Startup cache is already being built by another session`);
        return;
    }

    let scratchMongo = null;
    const startedAt = Date.now();
    try {
        fs.writeFileSync(path.join(cdsDir, '.java-bin'), JSON.stringify({ javaBin: javaBinPath, jarFingerprint }), 'utf8');
        removeCdsDir(scratchDir);
        fs.mkdirSync(path.join(scratchDir, 'data'), { recursive: true });
        fs.mkdirSync(path.join(scratchDir, 'home'), { recursive: true });
        fs.mkdirSync(path.join(scratchDir, 'tmp'), { recursive: true });
        discardPartialArchive(tempArchive);
        log('info', `[${name}] Building startup cache in the background (uses the editor's idle time; first launch only)…`);

        if (!fs.existsSync(extractedJar)) {
            await runToCompletion(javaBinPath, [
                '-Djarmode=tools', '-jar', originalJar, 'extract', '--destination', extractedDir,
            ], env, null, { timeoutMs: CDS_TRAINING_TIMEOUT_MS, lowPriority: true });
            assertNotCancelled();
        }

        scratchMongo = await startScratchMongo(scratchDir);
        assertNotCancelled();

        const isolatedArgs = springArgs
            .filter((arg) => !/^--(spring\.data\.mongodb\.uri|ontocode\.data\.dir|server\.port)=/.test(arg))
            .concat([
                `--spring.data.mongodb.uri=mongodb://127.0.0.1:${scratchMongo.port}/cds-training`,
                `--ontocode.data.dir=${path.join(scratchDir, 'data')}`,
                '--server.port=0',
            ]);
        await runToCompletion(javaBinPath, [
            '-Xmx512m',
            `-Duser.home=${path.join(scratchDir, 'home')}`,
            `-Djava.io.tmpdir=${path.join(scratchDir, 'tmp')}`,
            `-XX:ArchiveClassesAtExit=${tempArchive}`,
            '-Dspring.context.exit=onRefresh',
            ...WINDOWS_JVM_FLAGS,
            '-jar', extractedJar,
            ...isolatedArgs,
        ], env, path.join(cdsDir, 'training.log'), { timeoutMs: CDS_TRAINING_TIMEOUT_MS, lowPriority: true });
        assertNotCancelled();

        if (!fs.existsSync(tempArchive)) throw new Error('training produced no archive');
        fs.renameSync(tempArchive, archiveFile);
        fs.rmSync(failuresFile, { force: true });
        log('ok', `[${name}] Startup cache ready — next launch will be faster`);
        log('info', `[PERF] [${name}] background CDS build took ${Date.now() - startedAt}ms`);
    } catch (err) {
        discardPartialArchive(tempArchive);
        if (!startupCancelled) {
            fs.writeFileSync(failuresFile, String(failures + 1), 'utf8');
            log('warn', `[${name}] Startup cache build failed (${err.message}); will retry on a later launch`);
        }
    } finally {
        if (scratchMongo) {
            await killAndWait(scratchMongo.proc);
        }
        removeScratch(scratchDir);
        fs.rmSync(lockFile, { force: true });
    }
}

function killAndWait(proc) {
    return new Promise((resolve) => {
        if (proc.exitCode !== null) return resolve();
        const giveUp = setTimeout(resolve, 5000);
        proc.once('exit', () => {
            clearTimeout(giveUp);
            resolve();
        });
        try { proc.kill('SIGKILL'); } catch (_) { clearTimeout(giveUp); resolve(); }
    });
}

function removeScratch(dir) {
    try {
        fs.rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 });
    } catch (err) {
        log('warn', `Could not remove startup-cache scratch folder ${dir}: ${err.message}`);
    }
}

function runToCompletion(bin, args, extraEnv = {}, logFile = null, { timeoutMs = 0, onTick = null, tickMs = 10000, lowPriority = false } = {}) {
    return new Promise((resolve, reject) => {
        const env = { ...process.env, ...extraEnv };
        const p = spawn(bin, args, { env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
        if (lowPriority && p.pid) {
            try { os.setPriority(p.pid, os.constants.priority.PRIORITY_BELOW_NORMAL); } catch (_) {}
        }
        if (logFile) {
            const stream = fs.createWriteStream(logFile, { flags: 'w' });
            p.stdout.pipe(stream);
            p.stderr.pipe(stream);
        }

        const startedAt = Date.now();
        let ticker = null;
        let killer = null;
        helperProcesses.add(p);
        const cleanup = () => {
            clearInterval(ticker);
            clearTimeout(killer);
            helperProcesses.delete(p);
        };
        if (onTick) {
            ticker = setInterval(() => onTick(Math.round((Date.now() - startedAt) / 1000)), tickMs);
        }
        if (timeoutMs > 0) {
            killer = setTimeout(() => {
                try { p.kill('SIGKILL'); } catch (_) {}
                cleanup();
                reject(new Error(`timed out after ${Math.round(timeoutMs / 1000)}s`));
            }, timeoutMs);
        }

        p.on('exit', () => {
            cleanup();
            resolve();
        });
        p.on('error', (err) => {
            cleanup();
            reject(err);
        });
    });
}

async function startDesktop() {
    log('info', 'Starting Desktop service (auth + editor + plugin)…');
    const jar     = path.join(RESOURCES_DIR, 'jars', 'desktop.jar');
    const java    = javaBin();
    const logFile = path.join(LOGS_DIR, 'desktop.log');

    const mongoUri   = `mongodb://127.0.0.1:${MONGO_PORT}/ontocode-desktop`;
    const fusekiBase = `http://127.0.0.1:${FUSEKI_PORT}/ontocode`;

    log('info', `[Desktop] JAR:    ${jar}`);
    log('info', `[Desktop] Exists: ${fs.existsSync(jar)}`);
    log('info', `[Desktop] Java:   ${java}`);

    const heaps = jvmHeaps();
    log('info', `[Desktop] JVM heap: ${heaps.desktopXmx} (system RAM: ${heaps.totalGb} GB)`);

    const springArgs = [
        `--server.port=${DESKTOP_PORT}`,
        '--server.address=127.0.0.1',
        '--spring.profiles.active=desktop',
        `--spring.data.mongodb.uri=${mongoUri}`,

        `--ontocode.fuseki.queryEndpoint=${fusekiBase}/query`,
        `--ontocode.fuseki.updateEndpoint=${fusekiBase}/update`,
        `--ontocode.fuseki.gspEndpoint=${fusekiBase}/data`,
        `--sparql.endpointUrl=${fusekiBase}/query`,
        `--sparql.updateEndpointUrl=${fusekiBase}/update`,
        `--sparql.endpoint-url=${fusekiBase}/query`,

        `--ontocode.data.dir=${OWL_DATA_DIR}`,

        `--app.auth-service-url=http://127.0.0.1:${DESKTOP_PORT}`,
        `--auth.service.url=http://127.0.0.1:${DESKTOP_PORT}`,

        `--ontology.editor.url=http://127.0.0.1:${DESKTOP_PORT}`,

        '--jwt.secret=b250b2NvZGUtZGVza3RvcC1qd3Qtc2VjcmV0LWtleS12MQ==',
        `--app.base-url=http://127.0.0.1:${DESKTOP_PORT}`,

        '--spring.main.allow-bean-definition-overriding=true',

        '--spring.main.allow-circular-references=true',

        '--ontocode.desktop.mode=true',
        '--ontocode.desktop.owlapi-first=true',
        `--ontocode.desktop.plugins.bundled-dir=${path.join(RESOURCES_DIR, 'plugin-bundles')}`,
        '--app.email.enabled=false',
        '--openproject.enabled=false',
        '--spring.mail.host=localhost',
        '--management.health.mail.enabled=false',
        '--openproject.api.key=noop',
    ];

    const desktopEnv = {
        JAVA_TOOL_OPTIONS: '-Dfile.encoding=UTF-8',
        JAVA_OPTS: '',
        _JAVA_OPTIONS: '',
        ONTOCODE_DESKTOP_LAUNCH_KEY: DESKTOP_LAUNCH_KEY,
    };

    const { launchJar, cdsFlags } = await prepareCds(
        'Desktop', jar, path.join(DATA_DIR, 'cds', 'desktop'), springArgs, desktopEnv, java,
    );

    const args = [
        `-Xmx${heaps.desktopXmx}`,
        '-XX:+UseG1GC', '-XX:MaxGCPauseMillis=200',
        ...WINDOWS_JVM_FLAGS,
        ...cdsFlags,
        `-DLOG_DIR=${LOGS_DIR}`,
        '-jar', launchJar,
        ...springArgs,
    ];

    assertNotCancelled();
    desktopProcess = spawnService('Desktop', java, args, desktopEnv, logFile, { cwd: DATA_DIR });

    const startedAt = Date.now();
    const heartbeat = setInterval(() => {
        const elapsed = Math.round((Date.now() - startedAt) / 1000);
        log('info', `[Desktop] Still starting… (${elapsed}s)`);
    }, 3000);

    try {
        await waitForHttp(
            `http://127.0.0.1:${DESKTOP_PORT}/actuator/health`,
            STARTUP_HEALTH_TIMEOUT_MS,
            'Desktop',
            () => desktopProcess.exitCode !== null,
        );
    } finally {
        clearInterval(heartbeat);
    }
    log('ok', `Desktop service ready on port ${DESKTOP_PORT}`);
}

async function startSwrl() {
    const jar = path.join(RESOURCES_DIR, 'jars', 'swrl.jar');
    if (!fs.existsSync(jar)) {
        log('warn', 'swrl.jar not found — SWRL reasoner will not be available');
        return;
    }

    log('info', 'Starting SWRL reasoner service…');
    const logFile  = path.join(LOGS_DIR, 'swrl.log');
    const mongoUri = `mongodb://127.0.0.1:${MONGO_PORT}/ontocode-desktop`;

    const springArgs = [
        `--server.port=${SWRL_PORT}`,
        '--server.address=127.0.0.1',
        '--spring.profiles.active=desktop',
        `--spring.data.mongodb.uri=${mongoUri}`,
        `--app.auth-service-url=http://127.0.0.1:${DESKTOP_PORT}`,

        `--ontology.editor.service.url=http://127.0.0.1:${DESKTOP_PORT}`,
        '--jwt.secret=b250b2NvZGUtZGVza3RvcC1qd3Qtc2VjcmV0LWtleS12MQ==',
        '--app.email.enabled=false',
        '--spring.mail.host=localhost',
        '--management.health.mail.enabled=false',
    ];

    const swrlEnv = {
        JAVA_TOOL_OPTIONS: '-Dfile.encoding=UTF-8',
        JAVA_OPTS: '',
        _JAVA_OPTIONS: '',
    };

    const swrlJava = swrlJavaBin();
    log('info', `[SWRL] Java:   ${swrlJava}`);

    const { launchJar, cdsFlags } = await prepareCds(
        'SWRL', jar, path.join(DATA_DIR, 'cds', 'swrl'), springArgs, swrlEnv, swrlJava,
    );

    assertNotCancelled();
    swrlProcess = spawnService('SWRL', swrlJava, [
        '-Xmx512m',
        ...WINDOWS_JVM_FLAGS,
        ...cdsFlags,
        `-DLOG_DIR=${LOGS_DIR}`,
        '-jar', launchJar,
        ...springArgs,
    ], swrlEnv, logFile);

    await waitForHttp(`http://127.0.0.1:${SWRL_PORT}/actuator/health`, STARTUP_HEALTH_TIMEOUT_MS, 'SWRL',
        () => swrlProcess.exitCode !== null);
    log('ok', `SWRL reasoner ready on port ${SWRL_PORT}`);
}

function spawnService(name, bin, args, extraEnv = {}, logFile = null, spawnOptions = {}) {
    const env = { ...process.env, ...extraEnv };
    log('info', `[${name}] Spawning: ${bin}`);

    const child = spawn(bin, args, {
        env,
        stdio: ['ignore', 'pipe', 'pipe'],
        detached: false,
        windowsHide: true,
        ...spawnOptions,
    });

    const outputBuffer = [];
    let logStream = null;
    if (logFile) {
        try { logStream = fs.createWriteStream(logFile, { flags: 'a' }); }
        catch (e) { log('warn', `[${name}] Cannot open log file: ${e.message}`); }
    }

    const PROGRESS_MARKERS = [
        'Started', 'ERROR', 'Exception',
        'Bootstrapping Spring Data', 'Tomcat initialized', 'Tomcat started',
        'Root WebApplicationContext', 'Exposing', 'endpoints beneath',
    ];
    child.stdout.on('data', (data) => {
        if (logStream) logStream.write(data);
        data.toString().split('\n').forEach(line => {
            const t = line.trim();
            if (!t) return;
            if (outputBuffer.length >= 50) outputBuffer.shift();
            outputBuffer.push('[out] ' + t);
            if (PROGRESS_MARKERS.some(marker => t.includes(marker))) {
                log('info', `[${name}] ${t}`);
            }
        });
    });

    child.stderr.on('data', (data) => {
        if (logStream) logStream.write(data);
        data.toString().split('\n').forEach(line => {
            const t = line.trim();
            if (!t) return;
            if (outputBuffer.length >= 50) outputBuffer.shift();
            outputBuffer.push('[err] ' + t);
            log('warn', `[${name}] ${t}`);
        });
    });

    child.on('exit', (code) => {
        if (logStream) logStream.end();
        if (code !== 0 && code !== null) {
            log('error', `${name} exited with code ${code}`);
            outputBuffer.forEach(line => log('error', `[${name}] ${line.substring(0, 300)}`));
        }
    });

    child.on('error', (err) => {
        log('error', `Failed to start ${name}: ${err.message}`);
    });

    return child;
}

function waitForTcp(host, port, timeoutMs, label) {
    return new Promise((resolve, reject) => {
        const net = require('net');
        const deadline = Date.now() + timeoutMs;
        function attempt() {
            if (Date.now() > deadline) return reject(new Error(`Timeout waiting for ${label} on ${host}:${port}`));
            const sock = new net.Socket();
            sock.setTimeout(500);
            sock.once('connect', () => { sock.destroy(); resolve(); });
            sock.once('error',   () => { sock.destroy(); setTimeout(attempt, 500); });
            sock.once('timeout', () => { sock.destroy(); setTimeout(attempt, 500); });
            sock.connect(port, host);
        }
        attempt();
    });
}

function waitForHttp(url, timeoutMs, label, hasExited = null) {
    return new Promise((resolve, reject) => {
        const deadline = Date.now() + timeoutMs;
        function attempt() {
            if (hasExited && hasExited()) return reject(new Error(`${label} exited before it became ready`));
            if (Date.now() > deadline) return reject(new Error(`Timeout waiting for ${label} at ${url}`));
            const req = http.get(url, (res) => {
                if (res.statusCode >= 200 && res.statusCode < 400) resolve();
                else setTimeout(attempt, 1000);
                res.resume();
            });
            req.on('error', () => setTimeout(attempt, 1000));
            req.setTimeout(2000, () => { req.destroy(); setTimeout(attempt, 1000); });
        }
        attempt();
    });
}

function stopProcess(child, name, gracePeriodMs = 5000) {
    return new Promise((resolve) => {
        if (!child || child.killed || child.exitCode !== null) return resolve();
        let done = false;
        const finish = () => { if (!done) { done = true; resolve(); } };
        child.once('exit', finish);
        try { child.kill('SIGTERM'); } catch (_) {}
        setTimeout(() => {
            if (!done) {
                log('warn', `${name} did not stop in time; force-killing`);
                try { child.kill('SIGKILL'); } catch (_) {}
                finish();
            }
        }, gracePeriodMs);
    });
}
