

const { app } = require('electron');
const { spawn } = require('child_process');
const path = require('path');
const fs   = require('fs');
const http = require('http');
const os   = require('os');
const crypto = require('crypto');

const guard = require('./SharedGuard');
const policy = require('./ResourcePolicy');
const pg = require('./PostgresRuntime');
const logHygiene = require('./LogHygiene');

function jvmHeaps() {
    const budget = policy.computeBudget(os.totalmem());
    return {
        desktopXmx: `${budget.backendMb}m`,
        fusekiXmx: `${budget.fusekiMb}m`,
        swrlXmx: `${budget.swrlMb}m`,
        postgresBuffers: policy.postgresSharedBuffers(budget),
        totalGb: Math.round(budget.totalMb / 1024),
        budget,
    };
}

const RESOURCES_DIR = app.isPackaged
    ? path.join(process.resourcesPath, 'backend')
    : path.join(__dirname, '..', 'resources', 'backend');

const DATA_DIR        = path.join(app.getPath('userData'), 'data');
const POSTGRES_DATA_DIR = path.join(DATA_DIR, 'postgres');
const FUSEKI_DATA_DIR = path.join(DATA_DIR, 'fuseki');
const OWL_DATA_DIR    = path.join(DATA_DIR, 'ontologies');
const BLOB_DIR        = path.join(DATA_DIR, 'blobs');
const LOGS_DIR        = path.join(app.getPath('userData'), 'logs');
const FUSEKI_BASE_DIR = path.join(app.getPath('userData'), 'fuseki-base');
const JVM_TMP_DIR     = path.join(app.getPath('userData'), 'tmp');
const DIAG_DIR        = path.join(app.getPath('userData'), 'diagnostics');
const MAX_DIAG_FILES  = 3;
let heapDumpAllowed   = false;

function safetyFlags() {
    return policy.jvmSafetyFlags({ crashDir: DIAG_DIR, tmpDir: JVM_TMP_DIR, heapDump: heapDumpAllowed });
}

function exeNameFor(bin) {
    const base = path.basename(bin);
    return process.platform === 'win32' && !/\.exe$/i.test(base) ? `${base}.exe` : base;
}

const PLATFORM_JVM_FLAGS = [
    ...(process.platform === 'win32'
        ? [`-Djdk.net.unixdomain.tmpdir=${path.join(DATA_DIR, 'unix-sockets-disabled')}`]
        : []),
    ...(process.mas ? ['-XX:-UsePerfData'] : []),
];

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
const FUSEKI_QUERY_TIMEOUT_MS = 120000;

const DEFAULT_PORTS = {
    postgres: 15432,
    fuseki:  13030,
    desktop: 18083,
    swrl:    18084,
    proxy:   18085,
};

let POSTGRES_PORT = DEFAULT_PORTS.postgres;
let FUSEKI_PORT  = DEFAULT_PORTS.fuseki;
let DESKTOP_PORT = DEFAULT_PORTS.desktop;
let SWRL_PORT    = DEFAULT_PORTS.swrl;
let PROXY_PORT   = DEFAULT_PORTS.proxy;

let postgresProcess = null;

const LAZY_FUSEKI = process.env.ONTOCODE_LAZY_FUSEKI !== '0';

let fusekiProcess  = null;
let fusekiStartPromise = null;
let desktopProcess = null;

const LAZY_SWRL = process.env.ONTOCODE_LAZY_SWRL !== '0';
let swrlProcess    = null;
let swrlStartPromise = null;
let _logCallback   = null;
let _eventCallback = null;
let shuttingDown   = false;
let idleTimer      = null;
let idleTicks      = 0;
let diskWarned     = false;
let fusekiIdleStopped = false;
const idle = new policy.IdleTracker({ idleMs: policy.idleMsFromEnv() });
const restartBudget = new policy.RestartBudget({ max: 2 });

function emitEvent(event) {
    log(event.kind === 'restarted' ? 'info' : 'warn', event.message);
    if (_eventCallback) {
        try { _eventCallback(event); } catch (_) {}
    }
}

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
    POSTGRES_PORT = await findFreePort(DEFAULT_PORTS.postgres, 'PostgreSQL');
    FUSEKI_PORT  = await findFreePort(DEFAULT_PORTS.fuseki,  'Fuseki');
    DESKTOP_PORT = await findFreePort(DEFAULT_PORTS.desktop, 'Desktop');
    SWRL_PORT    = await findFreePort(DEFAULT_PORTS.swrl,    'SWRL');
    PROXY_PORT   = await findFreePort(DEFAULT_PORTS.proxy,   'Routing proxy');
    log('info', `Ports → PostgreSQL:${POSTGRES_PORT}  Fuseki:${FUSEKI_PORT}  Desktop:${DESKTOP_PORT}  SWRL:${SWRL_PORT}  Proxy:${PROXY_PORT}`);
}

const DESKTOP_LAUNCH_KEY = crypto.randomBytes(32).toString('hex');

let desktopSecrets = null;

function setDesktopSecrets(secrets) {
    desktopSecrets = secrets;
}

function requireDesktopSecrets() {
    if (!desktopSecrets || !desktopSecrets.jwtSecret || !desktopSecrets.adminPassword) {
        throw new Error('Desktop secrets must be loaded before the services start');
    }
    return desktopSecrets;
}

module.exports = {
    setDesktopSecrets,
    get DESKTOP_LAUNCH_KEY() { return DESKTOP_LAUNCH_KEY; },
    get POSTGRES_PORT() { return POSTGRES_PORT; },
    get FUSEKI_PORT()  { return FUSEKI_PORT; },
    get DESKTOP_PORT() { return DESKTOP_PORT; },
    get SWRL_PORT()    { return SWRL_PORT; },
    get PROXY_PORT()   { return PROXY_PORT; },
    get EDITOR_PORT()  { return DESKTOP_PORT; },
    get AUTH_PORT()    { return DESKTOP_PORT; },

    onLog(callback) { _logCallback = callback; },
    onServiceEvent(callback) { _eventCallback = callback; },
    killAllSync() { return guard.killAllSync(); },

    noteActivity({ swrl = false } = {}) {
        idle.touch('Fuseki');
        if (swrl) idle.touch('SWRL');
    },
    beginRequest() { idle.begin(); },
    endRequest() { idle.end(); },

    async resumeAfterIdle() {
        if (!fusekiIdleStopped || shuttingDown) return;
        try {
            await module.exports.ensureFuseki();
        } catch (err) {
            log('warn', `Could not restart Fuseki after idle stop: ${err.message}`);
        }
    },

    async startAll() {
        startupCancelled = false;
        shuttingDown = false;
        ensureDirs();
        prepareHygiene();
        const reaped = guard.reapStale();
        reaped.reaped.forEach((r) => log('warn', `Stopped leftover ${r.name} (pid ${r.pid}) from an earlier run that did not close cleanly`));
        const disk = policy.diskStatus(DATA_DIR);
        if (disk.level === 'critical') throw new Error(policy.diskMessage(disk));
        if (disk.level === 'low') {
            diskWarned = true;
            emitEvent({ kind: 'low-disk', name: 'Disk', message: policy.diskMessage(disk) });
        }
        startIdleWatcher();
        validateBackendBundles();
        await resolveAllPorts();
        await startPostgres();
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
                await Promise.all([startDesktop(), startSwrl()]);
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
        idle.touch('Fuseki');
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
        idle.touch('SWRL');
        const alive = () => Boolean(swrlProcess && !swrlProcess.killed && swrlProcess.exitCode === null);
        if (alive() && swrlProcess._ontoReady) {
            return { running: true, port: SWRL_PORT };
        }
        if (alive() && !swrlStartPromise) {
            await waitForHttp(`http://127.0.0.1:${SWRL_PORT}/actuator/health`, STARTUP_HEALTH_TIMEOUT_MS, 'SWRL',
                () => hasExited(swrlProcess));
            if (swrlProcess) swrlProcess._ontoReady = true;
            return { running: alive(), port: SWRL_PORT };
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
        shuttingDown = true;
        clearInterval(idleTimer);
        idleTimer = null;
        clearTimeout(trainingTimer);
        trainingTimer = null;
        pendingTraining.length = 0;
        helperProcesses.forEach((p) => { try { p.kill('SIGKILL'); } catch (_) {} });
        helperProcesses.clear();
        await stopProcess(swrlProcess,    'SWRL',    4000);
        await stopProcess(desktopProcess, 'Desktop', 10000);
        await stopProcess(fusekiProcess,  'Fuseki',  4000);
        await stopPostgres();
        swrlProcess    = null;
        desktopProcess = null;
        fusekiProcess  = null;
        postgresProcess = null;
        guard.killAllSync();
    },

    status() {

        const isRunning = (p) => Boolean(p && !p.killed && p.exitCode === null);
        return {
            postgres: isRunning(postgresProcess),
            fuseki:  isRunning(fusekiProcess),
            desktop: isRunning(desktopProcess),
            swrl:    isRunning(swrlProcess),
        };
    },
};

function ensureDirs() {
    [POSTGRES_DATA_DIR, FUSEKI_DATA_DIR, OWL_DATA_DIR, BLOB_DIR, LOGS_DIR, FUSEKI_BASE_DIR, DIAG_DIR].forEach(d => {
        if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true });
    });
}

function prepareHygiene() {
    logHygiene.resetDir(JVM_TMP_DIR);
    logHygiene.pruneDir(DIAG_DIR, MAX_DIAG_FILES);
    const rotated = logHygiene.rotateLogsAtStartup(LOGS_DIR, [
        { file: path.join(app.getPath('userData'), 'crash.log'), maxBytes: logHygiene.MAX_CRASH_LOG_BYTES },
    ]);
    if (rotated.length) log('info', `Rotated large log files: ${rotated.join(', ')}`);
    const heaps = jvmHeaps();
    const free = policy.freeBytes(DATA_DIR);
    heapDumpAllowed = process.env.ONTOCODE_HEAP_DUMP === '1'
        && free !== null
        && free > heaps.budget.backendMb * policy.MB * 1.5 + policy.LOW_DISK_BYTES;
}

function startIdleWatcher() {
    clearInterval(idleTimer);
    idleTicks = 0;
    idleTimer = setInterval(() => {
        idleTicks++;
        idle.due().forEach((name) => stopIdle(name));
        if (idleTicks % 10 === 0) checkDiskPeriodically();
    }, 60000);
    if (idleTimer.unref) idleTimer.unref();
}

function checkDiskPeriodically() {
    const disk = policy.diskStatus(DATA_DIR);
    if (disk.level === 'low' || disk.level === 'critical') {
        if (!diskWarned) {
            diskWarned = true;
            emitEvent({ kind: 'low-disk', name: 'Disk', message: policy.diskMessage(disk) });
        }
    } else if (disk.level === 'ok') {
        diskWarned = false;
    }
}

function stopIdle(name) {
    idle.untrack(name);
    if (name === 'Fuseki') {
        fusekiIdleStopped = true;
        log('info', 'Stopping Fuseki after a long idle period to free memory; it restarts when needed');
        stopProcess(fusekiProcess, 'Fuseki', 4000).then(() => { fusekiProcess = null; });
    } else if (name === 'SWRL') {
        log('info', 'Stopping the SWRL reasoner after a long idle period to free memory; it restarts when needed');
        stopProcess(swrlProcess, 'SWRL', 4000).then(() => { swrlProcess = null; });
    }
}

const SERVICE_LABELS = {
    PostgreSQL: 'The database',
    Desktop: 'The OntoCode backend',
    Fuseki: 'The graph and SPARQL service',
    SWRL: 'The rule reasoner',
};

function handleChildExit(name, child, code, tail) {
    if (shuttingDown || child._ontoStopping || !child._ontoReady) return;
    const restartable = name === 'PostgreSQL' || name === 'Desktop';
    let recovery = 'lazy';
    if (restartable) recovery = restartBudget.tryTake() ? 'restart' : 'none';
    const info = policy.classifyExit({ name, code, outputTail: tail, stopping: false, recovery });
    if (!info) return;
    emitEvent({ kind: 'died', name, outOfMemory: info.outOfMemory, message: info.message });
    if (recovery === 'restart') restartService(name);
}

async function restartService(name) {
    const label = SERVICE_LABELS[name] || name;
    try {
        if (name === 'PostgreSQL') {
            postgresProcess = await attemptStartPostgres();
        } else {
            await startDesktop();
        }
        if (!shuttingDown) emitEvent({ kind: 'restarted', name, message: `${label} is running again.` });
    } catch (err) {
        if (!shuttingDown) {
            emitEvent({ kind: 'restart-failed', name, message: `${label} could not be restarted (${err.message}). Please close and reopen OntoCode Studio.` });
        }
    }
}

function log(level, msg) {
    console.log(`[${level.toUpperCase()}] ${msg}`);
    if (_logCallback) _logCallback(level, msg);
}

function postgresRoot() {
    return path.join(RESOURCES_DIR, 'postgres', process.platform);
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

const STATUS_DLL_NOT_FOUND = 0xC0000135;

function isDllNotFound(code) {
    return typeof code === 'number' && (code >>> 0) === STATUS_DLL_NOT_FOUND;
}

async function attemptStartPostgres() {
    const root = postgresRoot();
    pg.clearStalePid(POSTGRES_DATA_DIR);
    await pg.applyPassword(root, POSTGRES_DATA_DIR, requireDesktopSecrets().dbPassword);
    const logFile = path.join(LOGS_DIR, 'postgres.log');
    const proc = spawnService('PostgreSQL', pg.binPath(root, 'postgres'),
        pg.serverArgs(POSTGRES_DATA_DIR, POSTGRES_PORT, jvmHeaps().postgresBuffers), {}, logFile);

    let onEarlyExit, onSpawnError;
    const earlyDeath = new Promise((_, reject) => {
        onEarlyExit = (code) => {
            const reason = isDllNotFound(code) ? ' (a required Windows library is missing next to postgres.exe)' : '';
            reject(new Error(`PostgreSQL exited with code ${code} before becoming ready${reason}`));
        };
        onSpawnError = (err) => reject(new Error(`PostgreSQL failed to start: ${err.message}`));
        proc.once('exit', onEarlyExit);
        proc.once('error', onSpawnError);
    });
    try {
        await Promise.race([
            pg.waitUntilAccepting(POSTGRES_PORT, 60000, () => hasExited(proc)),
            earlyDeath,
        ]);
        proc.removeListener('exit', onEarlyExit);
        proc.removeListener('error', onSpawnError);
        proc._ontoReady = true;
        return proc;
    } catch (err) {
        proc.removeListener('exit', onEarlyExit);
        proc.removeListener('error', onSpawnError);
        await stopPostgresProcess(proc);
        throw err;
    }
}

function stopPostgresProcess(proc) {
    return new Promise((resolve) => {
        if (!proc || proc.killed || proc.exitCode !== null) return resolve();
        proc._ontoStopping = true;
        const giveUp = setTimeout(() => {
            log('warn', 'PostgreSQL did not stop in time; force-killing');
            if (proc.pid) guard.killTree(proc.pid);
            try { proc.kill('SIGKILL'); } catch (_) {}
            resolve();
        }, 15000);
        proc.once('exit', () => { clearTimeout(giveUp); resolve(); });
        const ctl = spawn(pg.binPath(postgresRoot(), 'pg_ctl'),
            ['stop', '-D', POSTGRES_DATA_DIR, '-m', 'fast', '-t', '12'], { stdio: 'ignore', windowsHide: true });
        ctl.on('error', () => {
            if (process.platform === 'win32' && proc.pid) guard.killTree(proc.pid);
            else { try { proc.kill('SIGTERM'); } catch (_) {} }
        });
    });
}

async function stopPostgres() {
    await stopPostgresProcess(postgresProcess);
}

async function startPostgres() {
    log('info', 'Starting PostgreSQL…');
    const root = postgresRoot();
    const version = pg.checkVersion(root, POSTGRES_DATA_DIR);
    if (!version.ok) {
        throw new Error(`The local database in ${POSTGRES_DATA_DIR} was created by PostgreSQL ${version.dataMajor} but this version of OntoCode Studio ships PostgreSQL ${version.bundledMajor}. Your data has not been changed. Please install the OntoCode Studio version that matches it.`);
    }
    if (pg.clusterState(POSTGRES_DATA_DIR) !== 'present') log('info', 'Creating the local database (first start only)…');
    await pg.prepareCluster(root, POSTGRES_DATA_DIR, requireDesktopSecrets().dbPassword, { log });
    assertNotCancelled();
    postgresProcess = await attemptStartPostgres();
    log('ok', `PostgreSQL ready on port ${POSTGRES_PORT}`);
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
                      ja:context [ ja:cxtName "arq:queryTimeout" ; ja:cxtValue "${FUSEKI_QUERY_TIMEOUT_MS},${FUSEKI_QUERY_TIMEOUT_MS}" ] ] ;
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
        ...safetyFlags(),
        ...PLATFORM_JVM_FLAGS,
        '-jar', jar,
        '--port', String(FUSEKI_PORT),
        '--localhost',
        `--timeout=${FUSEKI_QUERY_TIMEOUT_MS},${FUSEKI_QUERY_TIMEOUT_MS}`,
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
    fusekiProcess._ontoReady = true;
    fusekiIdleStopped = false;
    idle.track('Fuseki');
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

const LGPL_LIB_DIR = path.join(RESOURCES_DIR, 'lib');
const DESKTOP_MAIN_CLASS = 'self.research.ontology.desktop.DesktopApplication';

function lgplLibFingerprint() {
    try {
        return fs.readdirSync(LGPL_LIB_DIR).sort()
            .map((f) => `${f}:${fs.statSync(path.join(LGPL_LIB_DIR, f)).size}`).join(',');
    } catch { return ''; }
}

function desktopJarArgs(jar, originalJar) {
    if (jar === originalJar) return [`-Dloader.path=${LGPL_LIB_DIR}`, '-jar', jar];
    return ['-cp', [jar, path.join(LGPL_LIB_DIR, '*')].join(path.delimiter), DESKTOP_MAIN_CLASS];
}

async function prepareCds(name, originalJar, cdsDir, springArgs, env, javaBinPath) {
    const extractedJar = path.join(cdsDir, 'extracted', path.basename(originalJar));
    const archiveFile  = path.join(cdsDir, `${name.toLowerCase()}.jsa`);
    const markerFile   = path.join(cdsDir, '.java-bin');

    const jarStat = fs.statSync(originalJar);
    const jarFingerprint = `${jarStat.mtimeMs}:${jarStat.size}` + (name === 'Desktop' ? `:${lgplLibFingerprint()}` : '');
    let recorded = null;
    if (fs.existsSync(markerFile)) {
        try { recorded = JSON.parse(fs.readFileSync(markerFile, 'utf8')); } catch {  }
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

async function startScratchPostgres(scratchDir) {
    const root = postgresRoot();
    const port = await findFreePort(15600, 'CDS training PostgreSQL');
    const dataDir = path.join(scratchDir, 'postgres');
    const password = crypto.randomBytes(24).toString('base64url');
    await pg.prepareCluster(root, dataDir, password, { log });
    await pg.applyPassword(root, dataDir, password);
    const proc = spawn(pg.binPath(root, 'postgres'), pg.serverArgs(dataDir, port, '64MB'),
        { stdio: 'ignore', windowsHide: true });
    helperProcesses.add(proc);
    guard.register('Scratch PostgreSQL', exeNameFor(pg.binPath(root, 'postgres')), proc);
    proc.once('exit', () => helperProcesses.delete(proc));
    await pg.waitUntilAccepting(port, 60000, () => proc.exitCode !== null);
    return { proc, port, password, dataDir };
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
    const diskLevel = policy.diskStatus(DATA_DIR).level;
    if (diskLevel === 'low' || diskLevel === 'critical') {
        log('info', `[${name}] Startup cache waiting for more free disk space`);
        return 'deferred';
    }
    fs.mkdirSync(cdsDir, { recursive: true });
    if (!acquireLock(lockFile)) {
        log('info', `[${name}] Startup cache is already being built by another session`);
        return;
    }

    let scratchDb = null;
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

        scratchDb = await startScratchPostgres(scratchDir);
        assertNotCancelled();

        const isolatedArgs = springArgs
            .filter((arg) => !/^--(ontocode\.data\.dir|server\.port)=/.test(arg))
            .concat([
                `--ontocode.data.dir=${path.join(scratchDir, 'data')}`,
                '--server.port=0',
            ]);
        await runToCompletion(javaBinPath, [
            '-Xmx512m',
            `-Duser.home=${path.join(scratchDir, 'home')}`,
            `-Djava.io.tmpdir=${path.join(scratchDir, 'tmp')}`,
            `-XX:ArchiveClassesAtExit=${tempArchive}`,
            '-Dspring.context.exit=onRefresh',
            ...PLATFORM_JVM_FLAGS,
            ...(name === 'Desktop' ? desktopJarArgs(extractedJar, originalJar) : ['-jar', extractedJar]),
            ...isolatedArgs,
        ], {
            ...env,
            ONTOCODE_DB_URL: pg.jdbcUrl(scratchDb.port),
            ONTOCODE_DB_USER: pg.DB_USER,
            ONTOCODE_DB_PASSWORD: scratchDb.password,
            ONTOCODE_BLOBS_DIR: path.join(scratchDir, 'blobs'),
        }, path.join(cdsDir, 'training.log'), { timeoutMs: CDS_TRAINING_TIMEOUT_MS, lowPriority: true });
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
        if (scratchDb) {
            await killAndWait(scratchDb.proc);
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
        guard.register('Helper', exeNameFor(bin), p);
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

function databaseEnv() {
    return {
        ONTOCODE_DB_URL: `${pg.jdbcUrl(POSTGRES_PORT)}?socketTimeout=60&connectTimeout=5`,
        ONTOCODE_DB_USER: pg.DB_USER,
        ONTOCODE_DB_PASSWORD: requireDesktopSecrets().dbPassword,
        ONTOCODE_BLOBS_DIR: BLOB_DIR,
    };
}

async function startDesktop() {
    log('info', 'Starting Desktop service (auth + editor + plugin)…');
    const jar     = path.join(RESOURCES_DIR, 'jars', 'desktop.jar');
    const java    = javaBin();
    const logFile = path.join(LOGS_DIR, 'desktop.log');

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
        `--ontocode.swrl.service.url=http://127.0.0.1:${PROXY_PORT}`,

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
        JWT_SECRET: requireDesktopSecrets().jwtSecret,
        APP_ADMIN_PASSWORD: requireDesktopSecrets().adminPassword,
        ...databaseEnv(),
    };

    const { launchJar, cdsFlags } = await prepareCds(
        'Desktop', jar, path.join(DATA_DIR, 'cds', 'desktop'), springArgs, desktopEnv, java,
    );

    const args = [
        `-Xmx${heaps.desktopXmx}`,
        '-XX:+UseG1GC', '-XX:MaxGCPauseMillis=200',
        ...safetyFlags(),
        ...PLATFORM_JVM_FLAGS,
        ...cdsFlags,
        `-DLOG_DIR=${LOGS_DIR}`,
        ...desktopJarArgs(launchJar, jar),
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
            () => hasExited(desktopProcess),
        );
    } finally {
        clearInterval(heartbeat);
    }
    desktopProcess._ontoReady = true;
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

    const springArgs = [
        `--server.port=${SWRL_PORT}`,
        '--server.address=127.0.0.1',
        '--spring.profiles.active=desktop',
        '--swrl.access-check.enabled=false',
        `--app.auth-service-url=http://127.0.0.1:${DESKTOP_PORT}`,

        `--ontology.editor.service.url=http://127.0.0.1:${DESKTOP_PORT}`,
        '--app.email.enabled=false',
        '--spring.mail.host=localhost',
        '--management.health.mail.enabled=false',
    ];

    const swrlEnv = {
        JAVA_TOOL_OPTIONS: '-Dfile.encoding=UTF-8',
        JAVA_OPTS: '',
        _JAVA_OPTIONS: '',
        JWT_SECRET: requireDesktopSecrets().jwtSecret,
        ...databaseEnv(),
    };

    const swrlJava = swrlJavaBin();
    log('info', `[SWRL] Java:   ${swrlJava}`);

    const { launchJar, cdsFlags } = await prepareCds(
        'SWRL', jar, path.join(DATA_DIR, 'cds', 'swrl'), springArgs, swrlEnv, swrlJava,
    );

    assertNotCancelled();
    swrlProcess = spawnService('SWRL', swrlJava, [
        `-Xmx${jvmHeaps().swrlXmx}`,
        ...safetyFlags(),
        ...PLATFORM_JVM_FLAGS,
        ...cdsFlags,
        `-DLOG_DIR=${LOGS_DIR}`,
        '-jar', launchJar,
        ...springArgs,
    ], swrlEnv, logFile);

    await waitForHttp(`http://127.0.0.1:${SWRL_PORT}/actuator/health`, STARTUP_HEALTH_TIMEOUT_MS, 'SWRL',
        () => hasExited(swrlProcess));
    if (swrlProcess) swrlProcess._ontoReady = true;
    idle.track('SWRL');
    log('ok', `SWRL reasoner ready on port ${SWRL_PORT}`);
}

function hasExited(proc) {
    return !proc || proc.exitCode !== null;
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
    guard.register(name, exeNameFor(bin), child);

    const outputBuffer = [];
    let logStream = null;
    if (logFile) {
        try { logStream = logHygiene.createRotatingStream(logFile); }
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
        handleChildExit(name, child, code, outputBuffer);
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
        child._ontoStopping = true;
        child.once('exit', finish);
        if (process.platform === 'win32' && child.pid) {
            guard.killTree(child.pid);
        } else {
            try { child.kill('SIGTERM'); } catch (_) {}
        }
        setTimeout(() => {
            if (!done) {
                log('warn', `${name} did not stop in time; force-killing`);
                if (child.pid) guard.killTree(child.pid);
                try { child.kill('SIGKILL'); } catch (_) {}
                finish();
            }
        }, gracePeriodMs);
    });
}
