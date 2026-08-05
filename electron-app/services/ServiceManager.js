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
 * Mongo starts first since Desktop depends on it being reachable. Fuseki and
 * SWRL are both lazy by default — skipped at startup and started on demand
 * the first time something actually needs them (Fuseki: a SPARQL/graph
 * operation; SWRL: the first /api/swrl/** proxy request) — since most
 * launches never touch either, and each is 10-30s of JVM startup that would
 * otherwise be paid on every single cold start.
 * Electron's window is shown once Mongo + Desktop (and Fuseki/SWRL, if not
 * lazy) have passed their health checks.
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
// Lazy SWRL: skip at startup, same rationale as Fuseki — most launches never
// touch the reasoner, so paying its ~10-30s JVM startup on every cold start
// is pure waste. Started on demand by the first /api/swrl/** proxy request.
const LAZY_SWRL = process.env.ONTOCODE_LAZY_SWRL !== '0';
let swrlProcess    = null;
let swrlStartPromise = null;
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
        if (LAZY_SWRL) {
            await startDesktop();
            log('info', 'SWRL reasoner deferred — starts on the first /api/swrl/** request');
        } else {
            // Opt-out path (ONTOCODE_LAZY_SWRL=0): Desktop and SWRL are
            // independent JVMs (split only due to an OWLAPI 4.x/5.x classpath
            // conflict) — start them concurrently rather than back-to-back.
            try {
                await Promise.all([startDesktop(), startSwrl()]);   // SWRL optional — skipped silently if swrl.jar absent
            } catch (err) {
                // Promise.all rejects on the first failure while the other JVM may
                // already be up or still starting — unlike a sequential await
                // (Desktop failing would mean SWRL was never spawned at all), so
                // clean up both here before propagating, or a Desktop-fails/
                // SWRL-succeeds race leaks an orphaned JVM.
                await Promise.all([
                    stopProcess(desktopProcess, 'Desktop', 4000),
                    stopProcess(swrlProcess, 'SWRL', 4000),
                ]);
                throw err;
            }
        }
    },

    async ensureFuseki() {
        // exitCode check: a crashed JVM is not `killed`, but it is not running either.
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
        // exitCode check: a crashed JVM is not `killed`, but it is not running either.
        if (swrlProcess && !swrlProcess.killed && swrlProcess.exitCode === null) {
            return { running: true, port: SWRL_PORT };
        }
        if (!swrlStartPromise) {
            swrlStartPromise = startSwrl().then(() => {
                swrlStartPromise = null;
                // startSwrl() resolves without spawning anything if swrl.jar is
                // missing — check the actual process state rather than assuming
                // success just because the promise didn't reject.
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
        // Same liveness check as ensureFuseki(): a crashed JVM is not `killed`,
        // but it is not running either.
        const isRunning = (p) => Boolean(p && !p.killed && p.exitCode === null);
        return {
            mongo:   isRunning(mongoProcess),
            fuseki:  isRunning(fusekiProcess),
            desktop: isRunning(desktopProcess),
            swrl:    isRunning(swrlProcess),
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

// SWRLAPI's bundled Drools 7.x/MVEL 2.x engine references java.lang.Compiler
// (removed in JDK 9) in a fallback path that only misfires under JDK 21's
// stricter internals, not JDK 17's — see ontology-swrl/pom.xml. SWRL gets its
// own dedicated JDK 17 JRE instead of sharing Desktop/Fuseki's JDK 21 one.
function swrlJavaBin() {
    const exe = process.platform === 'win32' ? 'java.exe' : 'java';
    const bundled17 = path.join(RESOURCES_DIR, 'jre17', 'bin', exe);
    if (fs.existsSync(bundled17)) return bundled17;
    if (process.env.JAVA17_HOME) {
        const jh = path.join(process.env.JAVA17_HOME, 'bin', exe);
        if (fs.existsSync(jh)) return jh;
    }
    // Fall back to the shared JRE — SWRL will likely hit NoClassDefFoundError:
    // java/lang/Compiler on JDK 21, but this preserves today's behavior for
    // installs built before the dedicated SWRL JRE existed, rather than
    // failing to launch SWRL at all.
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
    // after an unclean shutdown (crash, force-quit, OS kill). Every project gets
    // its own TDB2 dataset directory under fuseki-base/databases/{projectId}/ via
    // the admin API (dbType=tdb2) — each with its own tdb.lock — so this must glob
    // every project's database directory, not just the one legacy shared-dataset
    // path. Missing this meant a single project with a stale lock would fail
    // every sync attempt forever (no amount of "Try again" clears it), since
    // nothing else ever removes that specific file.
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

    // Fail fast if the JVM dies before answering the health check (port conflict,
    // missing jar, bad config) instead of burning the full 45s ping timeout.
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
        // Detach first so the kill below can't reject the already-settled race.
        fusekiProcess.removeListener('exit', onEarlyExit);
        fusekiProcess.removeListener('error', onSpawnError);
        // On ping timeout the JVM is still alive — kill it before dropping the
        // reference, or it keeps the port and TDB2 lock (every retry then fails
        // to bind) and stopAll() can no longer reach it.
        await stopProcess(fusekiProcess, 'Fuseki', 4000);
        fusekiProcess = null;
        throw err;
    } finally {
        // Detach so a later shutdown doesn't reject the (already settled) race.
        if (fusekiProcess) {
            fusekiProcess.removeListener('exit', onEarlyExit);
            fusekiProcess.removeListener('error', onSpawnError);
        }
    }
    log('ok', `Fuseki ready on port ${FUSEKI_PORT}`);
}

// Prepares a CDS-optimized launch for a Spring Boot fat jar, following Spring's
// documented recipe (docs.spring.io/spring-boot/reference/packaging/class-data-sharing.html):
//   1. Extract the jar into a plain-classpath layout (one-time; `-jar` fat-jar
//      loading uses a custom classloader that dynamic CDS can't see through).
//   2. Training run against the extracted jar: exits right after Spring's
//      context refresh (-Dspring.context.exit=onRefresh), producing an archive
//      that covers the actual application bean graph, not just JDK classes.
//   3. Real launches then use that archive via -XX:SharedArchiveFile.
// Both extraction and the archive persist across launches (only redone if
// missing) — only regenerated after an app update replaces the jar. Any
// failure at any step falls back to launching the original jar with no CDS
// flags rather than blocking startup — CDS is a speed optimization, not a
// correctness requirement.
// Dynamic CDS (-XX:ArchiveClassesAtExit) requires a base CDS archive already
// loaded for the JVM — normally bundled with a full JDK, but our jlink-built
// minimal JRE (kept small deliberately) doesn't include one. Without it,
// training silently no-ops (JVM logs a warning, produces no archive). Generate
// it once per bundled JRE; cheap (dumps a small default classlist, not the
// app's own classes) and safe to skip once the file exists.
async function ensureBaseCdsArchive(javaBinPath) {
    const jreHome     = path.dirname(path.dirname(javaBinPath));
    // Non-standard location for this jlink-minimized JRE (verified by locating
    // the file after a real -Xshare:dump) — a full JDK normally uses
    // lib/server/classes.jsa instead.
    const baseArchive = path.join(jreHome, 'bin', 'server', 'classes.jsa');
    if (fs.existsSync(baseArchive)) return;
    log('info', '[CDS] Generating base archive for bundled JRE (one-time)…');
    try {
        await runToCompletion(javaBinPath, ['-Xshare:dump'], {});
    } catch (err) {
        log('warn', `[CDS] Failed to generate base archive: ${err.message}`);
    }
}

async function prepareCds(name, originalJar, cdsDir, springArgs, env, javaBinPath) {
    const extractedDir = path.join(cdsDir, 'extracted');
    const extractedJar = path.join(extractedDir, path.basename(originalJar));
    const archiveFile  = path.join(cdsDir, `${name.toLowerCase()}.jsa`);
    const markerFile   = path.join(cdsDir, '.java-bin');
    const noCds = { launchJar: originalJar, cdsFlags: [] };

    // .jsa archives are tied to the exact JVM build that created them — a
    // different JVM just silently ignores an incompatible one rather than
    // erroring, so a stale cache from before this service was pointed at a
    // different bundled JRE (e.g. SWRL moving from JDK 21 to JDK 17) would
    // never self-heal on its own. They're also tied to the exact application
    // bytecode: the archive was trained against originalJar's classes, and an
    // app update that replaces originalJar leaves the OLD extracted+archived
    // copy in place with nothing to invalidate it — every updated user would
    // silently keep running pre-update code indefinitely. Only trust an
    // existing cache when the marker positively confirms it matches both this
    // exact java binary AND this exact jar (by mtime+size) — a missing/partial
    // marker means the cache predates this check or the source changed, so
    // treat that as stale too rather than assuming it's still valid.
    const jarStat = fs.statSync(originalJar);
    const jarFingerprint = `${jarStat.mtimeMs}:${jarStat.size}`;
    let recorded = null;
    if (fs.existsSync(markerFile)) {
        try { recorded = JSON.parse(fs.readFileSync(markerFile, 'utf8')); } catch { /* treat as stale below */ }
    }
    const isFresh = recorded && recorded.javaBin === javaBinPath && recorded.jarFingerprint === jarFingerprint;
    if (fs.existsSync(cdsDir) && !isFresh) {
        log('info', `[${name}] CDS cache missing/stale (JVM or jar changed) — regenerating`);
        fs.rmSync(cdsDir, { recursive: true, force: true });
    }

    await ensureBaseCdsArchive(javaBinPath);

    if (fs.existsSync(archiveFile) && fs.existsSync(extractedJar)) {
        return { launchJar: extractedJar, cdsFlags: [`-XX:SharedArchiveFile=${archiveFile}`] };
    }

    try {
        fs.mkdirSync(cdsDir, { recursive: true });
        fs.writeFileSync(markerFile, JSON.stringify({ javaBin: javaBinPath, jarFingerprint }), 'utf8');

        if (!fs.existsSync(extractedJar)) {
            log('info', `[${name}] Extracting jar for CDS (one-time)…`);
            await runToCompletion(javaBinPath, [
                '-Djarmode=tools', '-jar', originalJar, 'extract', '--destination', extractedDir,
            ], env);
        }

        log('info', `[${name}] Training CDS archive (one-time — this launch only, adds ~15-25s)…`);
        const trainingLog = path.join(cdsDir, 'training.log');
        await runToCompletion(javaBinPath, [
            `-XX:ArchiveClassesAtExit=${archiveFile}`,
            '-Dspring.context.exit=onRefresh',
            '-jar', extractedJar,
            ...springArgs,
        ], env, trainingLog);

        if (fs.existsSync(archiveFile)) {
            return { launchJar: extractedJar, cdsFlags: [`-XX:SharedArchiveFile=${archiveFile}`] };
        }
        log('warn', `[${name}] CDS training did not produce an archive — continuing without it`);
    } catch (err) {
        log('warn', `[${name}] CDS setup failed (${err.message}) — continuing without it`);
    }
    return noCds;
}

function runToCompletion(bin, args, extraEnv = {}, logFile = null) {
    return new Promise((resolve, reject) => {
        const env = { ...process.env, ...extraEnv };
        const p = spawn(bin, args, { env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
        if (logFile) {
            const stream = fs.createWriteStream(logFile, { flags: 'w' });
            p.stdout.pipe(stream);
            p.stderr.pipe(stream);
        }
        // Both `jarmode=tools extract` and the onRefresh training exit are
        // expected to succeed, but a non-zero code isn't necessarily fatal here
        // (e.g. the training run's forced early exit) — the caller checks for
        // the actual archive/jar file on disk rather than trusting this alone.
        p.on('exit', () => resolve());
        p.on('error', reject);
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

    // Spring Boot's own application args — independent of jar path / JVM CDS
    // flags, and shared between the CDS training run and the real launch below
    // so the two invocations can't drift out of sync.
    const springArgs = [
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
        // Plugin-service controllers (reasoner) are bundled too — their editor
        // calls must loop back to this JAR, not the Docker hostname default.
        `--ontology.editor.url=http://127.0.0.1:${DESKTOP_PORT}`,
        // JWT — shared secret used by all three bundled services
        '--jwt.secret=b250b2NvZGUtZGVza3RvcC1qd3Qtc2VjcmV0LWtleS12MQ==',
        `--app.base-url=http://127.0.0.1:${DESKTOP_PORT}`,
        // Allow same-named beans from merged modules to coexist
        '--spring.main.allow-bean-definition-overriding=true',
        // Allow circular references (sslBundleRegistry cycle in merged MongoDB auto-config)
        '--spring.main.allow-circular-references=true',
        // Disable cloud-only features
        '--ontocode.desktop.mode=true',
        '--ontocode.desktop.owlapi-first=true',
        `--ontocode.desktop.plugins.bundled-dir=${path.join(RESOURCES_DIR, 'plugin-bundles')}`,
        '--app.email.enabled=false',
        '--jira.enabled=false',
        '--spring.mail.host=localhost',
        '--management.health.mail.enabled=false',
        '--jira.api.token=noop',
    ];

    const desktopEnv = {
        JAVA_TOOL_OPTIONS: '-Dfile.encoding=UTF-8',
        JAVA_OPTS: '',
        _JAVA_OPTIONS: '',
    };

    // CDS on a Spring Boot fat jar (-jar desktop.jar) barely helps — almost all
    // application classes load through Spring's own LaunchedURLClassLoader, not
    // the JVM's standard classloader, and dynamic CDS only captures the latter
    // well. Spring's documented fix: extract the jar into a plain-classpath
    // layout, then do a real training run that exits right after context
    // refresh. See prepareCds() below.
    const { launchJar, cdsFlags } = await prepareCds(
        'Desktop', jar, path.join(DATA_DIR, 'cds', 'desktop'), springArgs, desktopEnv, java,
    );

    const args = [
        `-Xmx${heaps.desktopXmx}`,
        '-XX:+UseG1GC', '-XX:MaxGCPauseMillis=200',
        ...cdsFlags,
        `-DLOG_DIR=${LOGS_DIR}`,
        '-jar', launchJar,
        ...springArgs,
    ];

    desktopProcess = spawnService('Desktop', java, args, desktopEnv, logFile, { cwd: DATA_DIR });

    // Spring context refresh across the merged auth+editor+plugin jar can run
    // 15-25s with zero console output while classes load — without this the
    // splash screen looks frozen. Heartbeat keeps the progress UI moving.
    const startedAt = Date.now();
    const heartbeat = setInterval(() => {
        const elapsed = Math.round((Date.now() - startedAt) / 1000);
        log('info', `[Desktop] Still starting… (${elapsed}s)`);
    }, 3000);

    try {
        await waitForHttp(
            `http://127.0.0.1:${DESKTOP_PORT}/actuator/health`,
            120000,
            'Desktop',
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
        '--spring.profiles.active=desktop',
        `--spring.data.mongodb.uri=${mongoUri}`,
        `--app.auth-service-url=http://127.0.0.1:${DESKTOP_PORT}`,
        // SWRL calls back into the editor (e.g. ontology export) via
        // OntologyClientService, which defaults to the standalone
        // docker-compose port (8083) when unset — must loop back to the
        // merged desktop.jar's actual port instead, same fix as Desktop's
        // own --ontology.editor.url above.
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

    swrlProcess = spawnService('SWRL', swrlJava, [
        '-Xmx512m',
        ...cdsFlags,
        `-DLOG_DIR=${LOGS_DIR}`,
        '-jar', launchJar,
        ...springArgs,
    ], swrlEnv, logFile);

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

    // Milestones that appear in a Spring Boot console during context refresh —
    // forwarding these to the splash log keeps the progress UI moving during
    // the ~20s of classloading/component-scan that produces no other output.
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
