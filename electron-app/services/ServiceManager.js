/**
 * ServiceManager — OntoCode Desktop
 *
 * Manages backend processes required for the desktop app:
 *   1. MongoDB  (mongod)         — metadata storage
 *   2. Fuseki   (fuseki-server)  — RDF triple store
 *   3. Desktop  (desktop.jar)    — auth + OWL editor + plugin combined (one JVM)
 *   4. SWRL     (swrl.jar)       — SWRL reasoner (optional, separate JVM due to
 *                                   owlapi 4.x vs 5.x classpath conflict)
 *
 * All services are started sequentially (each waits for the previous to
 * pass a health check) then the Electron window is shown.
 *
 * On quit, services are stopped in reverse order.
 */

const { app } = require('electron');
const { spawn } = require('child_process');
const path = require('path');
const fs   = require('fs');
const http = require('http');
const os   = require('os');

// Compute JVM heap sizes based on available system RAM.
// Desktop jar (OWLAPI + Spring): 40% of RAM, 2g–12g
// Fuseki (TDB2 triple store):    28% of RAM, 1500m–8g
// A 1 GB OWL file expands to ~5-8x in OWLAPI heap, so machines with
// less than 16 GB will warn in logs but still try with what they have.
function jvmHeaps() {
    const totalGb = os.totalmem() / (1024 ** 3);
    const desktopGb = Math.min(Math.max(Math.floor(totalGb * 0.40), 2), 12);
    const fusekiGb  = Math.min(Math.max(Math.floor(totalGb * 0.28), 2), 8);
    return { desktopXmx: `${desktopGb}g`, fusekiXmx: `${fusekiGb}g`, totalGb: Math.round(totalGb) };
}

// ── Paths ────────────────────────────────────────────────────────────────────

const RESOURCES_DIR = app.isPackaged
    ? path.join(process.resourcesPath, 'backend')
    : path.join(__dirname, '..', 'resources', 'backend');

const DATA_DIR        = path.join(app.getPath('userData'), 'data');
const MONGO_DATA_DIR  = path.join(DATA_DIR, 'mongo');
const FUSEKI_DATA_DIR = path.join(DATA_DIR, 'fuseki');
const OWL_DATA_DIR    = path.join(DATA_DIR, 'ontologies');
const LOGS_DIR        = path.join(app.getPath('userData'), 'logs');
const FUSEKI_BASE_DIR = path.join(app.getPath('userData'), 'fuseki-base');

// ── Default ports ────────────────────────────────────────────────────────────
const DEFAULT_PORTS = {
    mongo:   27117,
    fuseki:  13030,
    desktop: 18083,
    swrl:    18084,
    proxy:   18085,
};

// ── Resolved ports (set during startAll, after auto-detection) ────────────────
let MONGO_PORT   = DEFAULT_PORTS.mongo;
let FUSEKI_PORT  = DEFAULT_PORTS.fuseki;
let DESKTOP_PORT = DEFAULT_PORTS.desktop;
let SWRL_PORT    = DEFAULT_PORTS.swrl;

// ── State ────────────────────────────────────────────────────────────────────
let mongoProcess   = null;
// Lazy Fuseki: skip at startup (OWLAPI-first desktop). Started on demand for SPARQL/graph.
const LAZY_FUSEKI = process.env.ONTOCODE_LAZY_FUSEKI !== '0';

let fusekiProcess  = null;
let fusekiStartPromise = null;
let desktopProcess = null;
let swrlProcess    = null;
let _logCallback   = null;

// ── Port auto-detection ───────────────────────────────────────────────────────

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

// ── Public API ───────────────────────────────────────────────────────────────

module.exports = {
    get MONGO_PORT()   { return MONGO_PORT; },
    get FUSEKI_PORT()  { return FUSEKI_PORT; },
    get DESKTOP_PORT() { return DESKTOP_PORT; },
    get SWRL_PORT()    { return SWRL_PORT; },
    get EDITOR_PORT()  { return DESKTOP_PORT; },
    get AUTH_PORT()    { return DESKTOP_PORT; },

    onLog(callback) { _logCallback = callback; },

    async startAll() {
        ensureDirs();
        validateBackendBundles();
        await resolveAllPorts();
        await startMongo();
        if (!LAZY_FUSEKI) {
            await startFuseki();
        } else {
            log('info', 'Fuseki deferred (OWLAPI-first desktop — starts when SPARQL/graph needs it)');
        }
        await startDesktop();
        await startSwrl();   // optional — skipped silently if swrl.jar absent
    },

    async ensureFuseki() {
        if (fusekiProcess && !fusekiProcess.killed) {
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

    async stopAll() {
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
        return {
            mongo:   mongoProcess   && !mongoProcess.killed,
            fuseki:  fusekiProcess  && !fusekiProcess.killed,
            desktop: desktopProcess && !desktopProcess.killed,
            swrl:    swrlProcess    && !swrlProcess.killed,
        };
    },
};

// ── Helpers ──────────────────────────────────────────────────────────────────

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

// ── Service starters ─────────────────────────────────────────────────────────

async function startMongo() {
    log('info', 'Starting MongoDB…');
    // Remove stale lock file left by a previous forced shutdown (SIGKILL).
    // WiredTiger has its own crash-recovery; the lockfile is advisory only.
    const mongoLock = path.join(MONGO_DATA_DIR, 'mongod.lock');
    try {
        if (fs.existsSync(mongoLock) && fs.readFileSync(mongoLock, 'utf8').trim()) {
            fs.unlinkSync(mongoLock);
            log('info', `Removed stale MongoDB lock: ${mongoLock}`);
        }
    } catch (_) {}
    const logFile = path.join(LOGS_DIR, 'mongo.log');
    mongoProcess = spawnService('MongoDB', mongoBin(), [
        '--dbpath', MONGO_DATA_DIR,
        '--port',   String(MONGO_PORT),
        '--logpath', logFile,
        '--logappend',
        '--bind_ip', '127.0.0.1',
    ], {});
    await waitForTcp('127.0.0.1', MONGO_PORT, 30000, 'MongoDB');
    log('ok', `MongoDB ready on port ${MONGO_PORT}`);
}

async function startFuseki() {
    log('info', 'Starting Apache Fuseki…');

    // Remove stale TDB2 lock files that cause Fuseki to open datasets read-only
    // after an unclean shutdown.
    const lockPaths = [
        path.join(FUSEKI_DATA_DIR, 'tdb.lock'),
        path.join(FUSEKI_DATA_DIR, 'Data-0001', 'tdb.lock'),
    ];
    lockPaths.forEach(p => {
        try { if (fs.existsSync(p)) { fs.unlinkSync(p); log('info', `Removed stale lock: ${p}`); } }
        catch (_) {}
    });

    const jar     = path.join(RESOURCES_DIR, 'jars', 'fuseki-server.jar');
    const logFile = path.join(LOGS_DIR, 'fuseki.log');

    // Write a Fuseki config that explicitly enables the GSP read-write endpoint.
    // Fuseki 6.x --loc only enables query/update; the /data GSP write endpoint
    // must be declared explicitly or imports fail with 405 Read-only.
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
        '-jar', jar,
        '--port', String(FUSEKI_PORT),
        '--config', configFile,
    ];

    fusekiProcess = spawnService('Fuseki', javaBin(), args, {
        FUSEKI_HOME: path.join(RESOURCES_DIR, 'fuseki'),
        FUSEKI_BASE: FUSEKI_BASE_DIR,
    }, logFile);

    await waitForHttp(`http://127.0.0.1:${FUSEKI_PORT}/$/ping`, 45000, 'Fuseki');
    log('ok', `Fuseki ready on port ${FUSEKI_PORT}`);
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

    const args = [
        `-Xmx${heaps.desktopXmx}`,
        '-XX:+UseG1GC', '-XX:MaxGCPauseMillis=200',
        `-DLOG_DIR=${LOGS_DIR}`,
        '-jar', jar,
        `--server.port=${DESKTOP_PORT}`,
        '--spring.profiles.active=desktop',
        `--spring.data.mongodb.uri=${mongoUri}`,
        // Fuseki / SPARQL
        `--ontocode.fuseki.queryEndpoint=${fusekiBase}/query`,
        `--ontocode.fuseki.updateEndpoint=${fusekiBase}/update`,
        `--ontocode.fuseki.gspEndpoint=${fusekiBase}/data`,
        `--sparql.endpointUrl=${fusekiBase}/query`,
        `--sparql.updateEndpointUrl=${fusekiBase}/update`,
        `--sparql.endpoint-url=${fusekiBase}/query`,
        // Data directory
        `--ontocode.data.dir=${OWL_DATA_DIR}`,
        // Auth service points to itself (auth is bundled in the same JAR)
        `--app.auth-service-url=http://127.0.0.1:${DESKTOP_PORT}`,
        `--auth.service.url=http://127.0.0.1:${DESKTOP_PORT}`,
        // JWT — shared secret used by all three bundled services
        '--jwt.secret=b250b2NvZGUtZGVza3RvcC1qd3Qtc2VjcmV0LWtleS12MQ==',
        `--app.base-url=http://127.0.0.1:${DESKTOP_PORT}`,
        // Allow same-named beans from merged modules to coexist
        '--spring.main.allow-bean-definition-overriding=true',
        // Allow circular references (sslBundleRegistry cycle in merged MongoDB auto-config)
        '--spring.main.allow-circular-references=true',
        // Disable cloud-only features
        '--ontocode.desktop.mode=true',
        `--ontocode.desktop.plugins.bundled-dir=${path.join(RESOURCES_DIR, 'plugin-bundles')}`,
        '--app.email.enabled=false',
        '--jira.enabled=false',
        '--spring.mail.host=localhost',
        '--management.health.mail.enabled=false',
        '--jira.api.token=noop',
    ];

    desktopProcess = spawnService('Desktop', java, args, {
        JAVA_TOOL_OPTIONS: '-Dfile.encoding=UTF-8',
        JAVA_OPTS: '',
        _JAVA_OPTIONS: '',
    }, logFile, { cwd: DATA_DIR });

    await waitForHttp(
        `http://127.0.0.1:${DESKTOP_PORT}/actuator/health`,
        120000,
        'Desktop',
    );
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

    swrlProcess = spawnService('SWRL', javaBin(), [
        '-Xmx512m',
        `-DLOG_DIR=${LOGS_DIR}`,
        '-jar', jar,
        `--server.port=${SWRL_PORT}`,
        '--spring.profiles.active=desktop',
        `--spring.data.mongodb.uri=${mongoUri}`,
        `--app.auth-service-url=http://127.0.0.1:${DESKTOP_PORT}`,
        '--jwt.secret=b250b2NvZGUtZGVza3RvcC1qd3Qtc2VjcmV0LWtleS12MQ==',
        '--app.email.enabled=false',
        '--spring.mail.host=localhost',
        '--management.health.mail.enabled=false',
    ], {
        JAVA_TOOL_OPTIONS: '-Dfile.encoding=UTF-8',
        JAVA_OPTS: '',
        _JAVA_OPTIONS: '',
    }, logFile);

    await waitForHttp(`http://127.0.0.1:${SWRL_PORT}/actuator/health`, 120000, 'SWRL');
    log('ok', `SWRL reasoner ready on port ${SWRL_PORT}`);
}

// ── Process helpers ───────────────────────────────────────────────────────────

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

    child.stdout.on('data', (data) => {
        if (logStream) logStream.write(data);
        data.toString().split('\n').forEach(line => {
            const t = line.trim();
            if (!t) return;
            if (outputBuffer.length >= 50) outputBuffer.shift();
            outputBuffer.push('[out] ' + t);
            if (t.includes('Started') || t.includes('ERROR') || t.includes('Exception')) {
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

function waitForHttp(url, timeoutMs, label) {
    return new Promise((resolve, reject) => {
        const deadline = Date.now() + timeoutMs;
        function attempt() {
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
