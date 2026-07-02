/**
 * build-desktop.js
 *
 * Unified desktop build with CLI flags (cross-platform).
 *
 * Usage:
 *   node scripts/build-desktop.js --help
 *   node scripts/build-desktop.js --full --win
 *   node scripts/build-desktop.js --java --web --portable
 *   node scripts/build-desktop.js --web --portable --portable-dir=E:\tmp\ontocode-portable
 *
 * npm:
 *   npm run build:desktop -- --full --win
 *   npm run dist:win
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const ELECTRON_APP = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(ELECTRON_APP, '..');
const WEBVIEW = path.join(REPO_ROOT, 'ontology-vscode-extension', 'webview-src');
const DESKTOP_JAVA = path.join(REPO_ROOT, 'ontology-desktop');
const DESKTOP_JAR_SRC = path.join(DESKTOP_JAVA, 'target', 'ontology-desktop-1.0.0.jar');
const DESKTOP_JAR_DEST = path.join(ELECTRON_APP, 'resources', 'backend', 'jars', 'desktop.jar');
const RENDERER_DIST = path.join(ELECTRON_APP, 'renderer', 'dist');
const WEBVIEW_DIST = path.join(WEBVIEW, 'dist');
const PLUGINS_ROOT = path.join(REPO_ROOT, 'plugins');
const PLUGIN_BUNDLES_DIR = path.join(ELECTRON_APP, 'resources', 'backend', 'plugin-bundles');
const PLUGINS_MANIFEST_SRC = path.join(__dirname, 'plugins-manifest.json');

const HELP = `
OntoCode desktop build

Flags (combine as needed):
  --full              Java + web + resources + copy renderer (recommended for releases)
  --quick             Dev iteration: java + web + portable only (~3–5 min). Skips plugins,
                      JRE rebuild, and prepare-resources when desktop.jar already exists.
  --patch             Fast update of an EXISTING portable folder (~1–3 min). Use with
                      --patch-java and/or --patch-web (default: both).
  --patch-java        Patch only desktop.jar (backend fix, ~60–90s). Restart app after.
  --patch-web         Patch only app.asar (UI fix, ~1–2 min). No Maven.
  --java              mvn package ontology-desktop → desktop.jar
  --web               npm run build:electron in webview-src
  --resources         prepare-resources (Fuseki, Mongo, JRE, copy desktop.jar)
  --resources --build-java   Same as --resources but run Maven if desktop.jar missing
  --renderer          Copy webview dist → electron-app/renderer/dist
  --win | --mac | --linux   Run electron-builder for that platform (implies --full steps)
  --portable          Pack app.asar into a portable folder (no NSIS)
  --portable-dir=PATH Portable output root (default: E:\\tmp\\ontocode-portable on Windows)
  --staging-dir=PATH  ASAR staging dir (default: E:\\tmp\\ontocode-asar-staging)
  --skip-jre          Pass through to prepare-resources (skip JRE download/jlink)
  --plugins           Build JS plugin bundles + copy to resources/backend/plugin-bundles
                      (seeded into local Mongo on first desktop run)

Examples:
  node scripts/build-desktop.js --full --resources --plugins --portable
  node scripts/build-desktop.js --full --win
  node scripts/build-desktop.js --quick --portable-dir=E:\tmp\ontocode-portable-11
  node scripts/build-desktop.js --patch-java --portable-dir=E:\tmp\ontocode-portable-11
  node scripts/build-desktop.js --patch-web --portable-dir=E:\tmp\ontocode-portable-11
`;

function parseArgs(argv) {
    const opts = {
        java: false,
        web: false,
        resources: false,
        buildJavaInResources: false,
        renderer: false,
        installer: false,
        portable: false,
        platform: null,
        skipJre: false,
        plugins: false,
        quick: false,
        patch: false,
        skipStagingDeps: false,
        portableDir: process.platform === 'win32'
            ? 'E:\\tmp\\ontocode-portable'
            : path.join(process.env.HOME || '/tmp', 'ontocode-portable'),
        stagingDir: process.platform === 'win32'
            ? 'E:\\tmp\\ontocode-asar-staging'
            : path.join(process.env.HOME || '/tmp', 'ontocode-asar-staging'),
        help: false,
    };

    for (const arg of argv) {
        if (arg === '--help' || arg === '-h') opts.help = true;
        else if (arg === '--full') {
            opts.java = true;
            opts.web = true;
            opts.resources = true;
            opts.renderer = true;
            opts.plugins = true;
        } else if (arg === '--quick') {
            opts.quick = true;
            opts.java = true;
            opts.web = true;
            opts.renderer = true;
            opts.portable = true;
            opts.skipJre = true;
        } else if (arg === '--patch') {
            opts.patch = true;
            opts.portable = true;
            opts.skipJre = true;
            opts.java = true;
            opts.web = true;
            opts.renderer = true;
        } else if (arg === '--patch-java') {
            opts.patch = true;
            opts.portable = true;
            opts.skipJre = true;
            opts.java = true;
        } else if (arg === '--patch-web') {
            opts.patch = true;
            opts.portable = true;
            opts.skipJre = true;
            opts.web = true;
            opts.renderer = true;
        } else if (arg === '--plugins') opts.plugins = true;
        else if (arg === '--java') opts.java = true;
        else if (arg === '--web') opts.web = true;
        else if (arg === '--resources') opts.resources = true;
        else if (arg === '--build-java') opts.buildJavaInResources = true;
        else if (arg === '--renderer') opts.renderer = true;
        else if (arg === '--portable') opts.portable = true;
        else if (arg === '--win') { opts.installer = true; opts.platform = 'win'; }
        else if (arg === '--mac') { opts.installer = true; opts.platform = 'mac'; }
        else if (arg === '--linux') { opts.installer = true; opts.platform = 'linux'; }
        else if (arg === '--skip-jre') opts.skipJre = true;
        else if (arg.startsWith('--portable-dir=')) opts.portableDir = arg.slice('--portable-dir='.length);
        else if (arg.startsWith('--staging-dir=')) opts.stagingDir = arg.slice('--staging-dir='.length);
        else console.warn(`Unknown argument: ${arg} (use --help)`);
    }

    if (opts.installer && opts.platform) {
        opts.java = true;
        opts.web = true;
        opts.resources = true;
        opts.renderer = true;
        opts.plugins = true;
    }

    if (opts.portable && !opts.web) {
        opts.web = true;
        opts.renderer = true;
        opts.resources = true;
    }

    return opts;
}

function run(cmd, cwd, label) {
    console.log(`\n→ ${label}`);
    console.log(`  ${cmd}`);
    execSync(cmd, { cwd, stdio: 'inherit', env: process.env });
}

function ensureDir(d) {
    if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true });
}

function copyDir(src, dest) {
    ensureDir(dest);
    for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
        const s = path.join(src, entry.name);
        const d = path.join(dest, entry.name);
        if (entry.isDirectory()) copyDir(s, d);
        else fs.copyFileSync(s, d);
    }
}

/** Overlay-copy without deleting dest root (avoids EPERM when portable app holds backend/JRE open). */
function syncDirOverlay(src, dest) {
    ensureDir(dest);
    for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
        const s = path.join(src, entry.name);
        const d = path.join(dest, entry.name);
        if (entry.isDirectory()) {
            syncDirOverlay(s, d);
        } else {
            ensureDir(path.dirname(d));
            fs.copyFileSync(s, d);
        }
    }
}

function sleepSync(ms) {
    if (process.platform === 'win32') {
        try {
            execSync(`powershell -NoProfile -Command "Start-Sleep -Milliseconds ${ms}"`, { stdio: 'ignore' });
            return;
        } catch { /* fall through */ }
    }
    const end = Date.now() + ms;
    while (Date.now() < end) { /* busy wait */ }
}

function unlinkWithRetry(filePath, retries = 8) {
    for (let i = 0; i < retries; i++) {
        try {
            if (fs.existsSync(filePath)) fs.unlinkSync(filePath);
            return;
        } catch (err) {
            if (i === retries - 1) throw err;
            if (err.code !== 'EPERM' && err.code !== 'EBUSY' && err.code !== 'EACCES') throw err;
            sleepSync(400 * (i + 1));
        }
    }
}

function portableLockHint(portableDir) {
    return (
        `\nPortable folder is locked: ${portableDir}\n` +
        '  • Quit OntoCode Desktop if it was started from that folder\n' +
        '  • End stray java.exe / mongod.exe from an old portable run (Task Manager)\n' +
        '  • Or use a fresh output path: --portable-dir=E:\\tmp\\ontocode-portable-2\n' +
        'Then retry packaging only:\n' +
        '  node scripts/build-desktop.js --portable --renderer\n'
    );
}

function buildJava(quick) {
    run('mvn install -N -q', REPO_ROOT, 'Maven: install root POM');
    run(
        'mvn install -pl shared/common-models,shared/common-utils -DskipTests -q',
        REPO_ROOT,
        'Maven: shared modules',
    );
    run('mvn install -pl ontology-auth,ontology-editor -DskipTests -q', REPO_ROOT, 'Maven: auth + editor');
    run('mvn install -DskipTests -q', path.join(REPO_ROOT, 'ontology-plugin-service'), 'Maven: plugin-service');
    const mvnGoal = quick ? 'mvn package -DskipTests -q' : 'mvn clean package -DskipTests -q';
    run(mvnGoal, DESKTOP_JAVA, 'Maven: ontology-desktop (auth + editor + plugin)');
    if (!fs.existsSync(DESKTOP_JAR_SRC)) {
        throw new Error(`Missing ${DESKTOP_JAR_SRC} after Maven build`);
    }
    ensureDir(path.dirname(DESKTOP_JAR_DEST));
    fs.copyFileSync(DESKTOP_JAR_SRC, DESKTOP_JAR_DEST);
    console.log(`  ✓  desktop.jar → ${DESKTOP_JAR_DEST}`);
}

/** Incremental Maven — only editor + desktop fat jar (~60–90s vs 4+ min full chain). */
function buildJavaPatch() {
    run(
        'mvn install -pl ontology-editor -DskipTests -q',
        REPO_ROOT,
        'Maven patch: ontology-editor install',
    );
    run('mvn clean package -DskipTests -q', DESKTOP_JAVA, 'Maven patch: ontology-desktop repackage');
    if (!fs.existsSync(DESKTOP_JAR_SRC)) {
        throw new Error(`Missing ${DESKTOP_JAR_SRC} after patch Maven build`);
    }
    ensureDir(path.dirname(DESKTOP_JAR_DEST));
    fs.copyFileSync(DESKTOP_JAR_SRC, DESKTOP_JAR_DEST);
    console.log(`  ✓  desktop.jar → ${DESKTOP_JAR_DEST}`);
}

function patchPortableJarOnly(opts) {
    const portable = path.resolve(opts.portableDir);
    const jarDest = path.join(portable, 'resources', 'backend', 'jars', 'desktop.jar');
    if (!fs.existsSync(DESKTOP_JAR_DEST)) {
        throw new Error('desktop.jar missing — run with --patch-java first');
    }
    if (!fs.existsSync(portable)) {
        throw new Error(`Portable folder missing: ${portable}\n  Run --quick once to create it.`);
    }
    ensureDir(path.dirname(jarDest));
    fs.copyFileSync(DESKTOP_JAR_DEST, jarDest);
    console.log(`\n→ Patch desktop.jar only`);
    console.log(`  ✓  ${jarDest}`);
    console.log('\n  Quit OntoCode completely (Task Manager: end java.exe), then restart the portable exe.');
}

function buildPlugins() {
    const manifest = JSON.parse(fs.readFileSync(PLUGINS_MANIFEST_SRC, 'utf8'));
    if (fs.existsSync(PLUGIN_BUNDLES_DIR)) {
        fs.rmSync(PLUGIN_BUNDLES_DIR, { recursive: true, force: true });
    }
    ensureDir(PLUGIN_BUNDLES_DIR);
    fs.copyFileSync(PLUGINS_MANIFEST_SRC, path.join(PLUGIN_BUNDLES_DIR, 'plugins-manifest.json'));

    for (const plugin of manifest) {
        const dir = path.join(PLUGINS_ROOT, plugin.pluginId);
        if (!fs.existsSync(dir)) {
            throw new Error(`Plugin source not found: ${dir}`);
        }
        const nodeModules = path.join(dir, 'node_modules');
        if (!fs.existsSync(nodeModules)) {
            run('npm install', dir, `npm install: ${plugin.pluginId}`);
        }
        run('npm run build', dir, `Build plugin: ${plugin.pluginId}`);
        const bundle = path.join(dir, 'dist', 'index.js');
        if (!fs.existsSync(bundle)) {
            throw new Error(`Plugin bundle missing after build: ${bundle}`);
        }
        const destDir = path.join(PLUGIN_BUNDLES_DIR, plugin.pluginId);
        ensureDir(destDir);
        fs.copyFileSync(bundle, path.join(destDir, 'index.js'));
        console.log(`  ✓  ${plugin.pluginId} → plugin-bundles/${plugin.pluginId}/index.js`);
    }
    console.log(`  ✓  ${manifest.length} plugins bundled for offline desktop`);
}

function buildWeb() {
    // Vite must resolve deps from webview-src/node_modules, not repo root.
    const vitePluginReact = path.join(WEBVIEW, 'node_modules', '@vitejs', 'plugin-react');
    if (!fs.existsSync(vitePluginReact)) {
        run('npm ci', WEBVIEW, 'Frontend: npm ci in webview-src (installs @vitejs/plugin-react, vite, …)');
    }
    run('npm run build:electron', WEBVIEW, 'Frontend: vite build:electron');
    if (!fs.existsSync(path.join(WEBVIEW_DIST, 'index.html'))) {
        throw new Error(`Missing ${WEBVIEW_DIST}/index.html after web build`);
    }
}

function copyRenderer() {
    if (!fs.existsSync(WEBVIEW_DIST)) {
        throw new Error(`Run --web first; missing ${WEBVIEW_DIST}`);
    }
    if (fs.existsSync(RENDERER_DIST)) fs.rmSync(RENDERER_DIST, { recursive: true, force: true });
    copyDir(WEBVIEW_DIST, RENDERER_DIST);
    console.log(`  ✓  renderer/dist synced from webview-src/dist`);
}

function runPrepareResources(opts) {
    const args = ['node', path.join(__dirname, 'prepare-resources.js')];
    if (opts.buildJavaInResources) args.push('--build-java');
    if (opts.skipJre) args.push('--skip-jre');
    // Do not rebuild Java here when buildJava() already ran — only copy jars + JRE.
    console.log(`\n→ prepare-resources`);
    const env = { ...process.env };
    if (opts.platform === 'win' || process.env.TARGET_PLATFORM === 'win32') {
        env.TARGET_PLATFORM = 'win32';
    }
    execSync(args.join(' '), { cwd: ELECTRON_APP, stdio: 'inherit', env });
}

/** Production deps for app.asar — always refresh so new packages (e.g. electron-updater) are included. */
function installStagingDeps(staging) {
    const lockSrc = path.join(ELECTRON_APP, 'package-lock.json');
    if (fs.existsSync(lockSrc)) {
        fs.copyFileSync(lockSrc, path.join(staging, 'package-lock.json'));
    }
    const nm = path.join(staging, 'node_modules');
    if (fs.existsSync(nm)) {
        fs.rmSync(nm, { recursive: true, force: true });
    }
    // Ensure electron-app itself has deps before staging install
    if (!fs.existsSync(path.join(ELECTRON_APP, 'node_modules', 'electron-updater'))) {
        run('npm install --omit=dev --no-audit --no-fund', ELECTRON_APP, 'electron-app: npm install --omit=dev');
    }
    const cmd = fs.existsSync(path.join(staging, 'package-lock.json'))
        ? 'npm ci --omit=dev --no-audit --no-fund'
        : 'npm install --omit=dev --no-audit --no-fund';
    run(cmd, staging, 'Portable staging: production dependencies');
}

function syncPortableStaging(opts) {
    const staging = path.resolve(opts.stagingDir);
    ensureDir(staging);

    const files = ['main.js', 'preload.js', 'splash.html', 'splash-preload.js', 'package.json'];
    for (const f of files) {
        fs.copyFileSync(path.join(ELECTRON_APP, f), path.join(staging, f));
    }
    copyDir(path.join(ELECTRON_APP, 'services'), path.join(staging, 'services'));
    copyDir(path.join(ELECTRON_APP, 'scripts'), path.join(staging, 'scripts'));

    const stagingRenderer = path.join(staging, 'renderer', 'dist');
    if (fs.existsSync(stagingRenderer)) fs.rmSync(stagingRenderer, { recursive: true, force: true });
    copyDir(fs.existsSync(RENDERER_DIST) ? RENDERER_DIST : WEBVIEW_DIST, stagingRenderer);

    if (!opts.skipStagingDeps) {
        installStagingDeps(staging);
    } else {
        console.log('  ✓  Skipping staging npm install (patch mode)');
    }

    console.log(`  ✓  Staging → ${staging}`);
    return staging;
}

function ensurePortableShell(portableDir) {
    const exeName = process.platform === 'win32' ? 'OntoCode.exe' : 'OntoCode';
    const portableExe = path.join(portableDir, exeName);
    if (fs.existsSync(portableExe)) {
        return;
    }

    const winUnpacked = path.join(ELECTRON_APP, 'dist-electron', 'win-unpacked');
    if (!fs.existsSync(winUnpacked)) {
        console.warn(`\n⚠  Portable folder has no ${exeName} and ${winUnpacked} is missing.`);
        console.warn('   Run once: npm run dist:win');
        console.warn(`   Or copy dist-electron/win-unpacked/* into ${portableDir} (keep resources/ from this build).`);
        return;
    }

    console.log('\n→ Seed portable Electron shell (exe + DLLs; keep fresh resources/)');
    for (const entry of fs.readdirSync(winUnpacked, { withFileTypes: true })) {
        if (entry.name === 'resources') {
            continue;
        }
        const s = path.join(winUnpacked, entry.name);
        const d = path.join(portableDir, entry.name);
        if (entry.isDirectory()) {
            copyDir(s, d);
        } else {
            ensureDir(path.dirname(d));
            fs.copyFileSync(s, d);
        }
    }
    console.log(`  ✓  ${exeName} + runtime → ${portableDir}`);
}

function packPortable(opts) {
    const portable = path.resolve(opts.portableDir);
    ensureDir(portable);
    ensurePortableShell(portable);
    const staging = syncPortableStaging(opts);
    const resourcesDir = path.join(portable, 'resources');
    ensureDir(resourcesDir);

    // Pack outside portable/resources so a locked sibling backend/ folder cannot block asar.
    const asarTmp = path.join(path.dirname(staging), 'ontocode-app.asar.tmp');
    try {
        unlinkWithRetry(asarTmp);
    } catch (err) {
        throw new Error(`Cannot write temp asar at ${asarTmp}: ${err.message}${portableLockHint(portable)}`);
    }

    run(
        `npx --yes @electron/asar pack "${staging}" "${asarTmp}"`,
        ELECTRON_APP,
        'Pack app.asar (temp)',
    );

    const asarFinal = path.join(resourcesDir, 'app.asar');
    console.log('\n→ Install app.asar into portable');
    try {
        unlinkWithRetry(asarFinal);
        fs.copyFileSync(asarTmp, asarFinal);
        unlinkWithRetry(asarTmp);
        console.log(`  ✓  app.asar → ${asarFinal}`);
    } catch (err) {
        throw new Error(`Cannot update ${asarFinal}: ${err.message}${portableLockHint(portable)}`);
    }

    const backendSrc = path.join(ELECTRON_APP, 'resources', 'backend');
    const backendDest = path.join(resourcesDir, 'backend');
    if (opts.patch && !opts.java) {
        console.log('\n→ Patch web only — skipping backend jar overlay');
    } else if (fs.existsSync(backendSrc)) {
        console.log('\n→ Sync backend resources (overlay, no full delete)');
        try {
            syncDirOverlay(backendSrc, backendDest);
            console.log(`  ✓  Backend resources → ${backendDest}`);
        } catch (err) {
            throw new Error(`Cannot sync backend to ${backendDest}: ${err.message}${portableLockHint(portable)}`);
        }
    }

    if (fs.existsSync(DESKTOP_JAR_DEST)) {
        const portableJar = path.join(backendDest, 'jars', 'desktop.jar');
        ensureDir(path.dirname(portableJar));
        fs.copyFileSync(DESKTOP_JAR_DEST, portableJar);
        console.log(`  ✓  desktop.jar → portable`);
    }

    const pluginBundles = path.join(backendDest, 'plugin-bundles');
    if (fs.existsSync(pluginBundles)) {
        const count = fs.readdirSync(pluginBundles, { withFileTypes: true })
            .filter((e) => e.isDirectory()).length;
        console.log(`  ✓  plugin-bundles (${count} plugins)`);
    }

    console.log(`\n✓  Portable build ready: ${portable}`);
    console.log(`   Run: ${path.join(portable, process.platform === 'win32' ? 'OntoCode.exe' : 'OntoCode')}`);
}

function runInstaller(platform) {
    if (platform === 'mac') {
        const { ensure512 } = require('./ensure-build-icons');
        console.log('\n→ macOS icon (512x512)');
        ensure512();
    }
    const flag = platform === 'win' ? '--win' : platform === 'mac' ? '--mac' : '--linux';
    run(`npx electron-builder ${flag}`, ELECTRON_APP, `electron-builder ${flag}`);
}

function verifyBundle() {
    const checks = [
        ['desktop.jar', DESKTOP_JAR_DEST],
        ['renderer/index.html', path.join(RENDERER_DIST, 'index.html')],
    ];
    let ok = true;
    console.log('\n=== Bundle verification ===');
    for (const [label, p] of checks) {
        const exists = fs.existsSync(p);
        console.log(`  ${exists ? '✓' : '✗'}  ${label}`);
        if (!exists) ok = false;
    }
    if (!ok) {
        console.error('\nBundle incomplete. Use --full or pass --java --web --resources --renderer\n');
        process.exit(1);
    }
}

async function main() {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) {
        console.log(HELP);
        return;
    }

    const nothing = !opts.java && !opts.web && !opts.resources && !opts.renderer
        && !opts.installer && !opts.portable && !opts.plugins;
    if (nothing) {
        console.log(HELP);
        process.exit(1);
    }

    console.log('\n=== OntoCode build-desktop ===');
    console.log(JSON.stringify(opts, null, 2));

    if (opts.java) {
        if (opts.patch) buildJavaPatch();
        else buildJava(opts.quick);
    }
    if (opts.plugins) buildPlugins();
    if (opts.web) buildWeb();
    const jarReady = fs.existsSync(DESKTOP_JAR_DEST);
    if (opts.resources) {
        runPrepareResources(opts);
    } else if (opts.quick && !jarReady) {
        console.log('\n→ quick: desktop.jar missing — running prepare-resources (copy only)');
        runPrepareResources({ ...opts, skipJre: true });
    } else if (opts.quick && jarReady) {
        console.log('\n→ quick: skipping prepare-resources (desktop.jar already present)');
    }
    if (opts.renderer) copyRenderer();

    if (opts.patch && opts.java && !opts.web) {
        patchPortableJarOnly(opts);
    } else if (opts.portable) {
        packPortable({ ...opts, skipStagingDeps: opts.patch });
    }
    if (opts.installer) {
        verifyBundle();
        runInstaller(opts.platform || 'win');
    } else if (opts.java || opts.web) {
        verifyBundle();
    }

    console.log('\n✓  Done.\n');
}

main().catch((err) => {
    console.error(err.message || err);
    process.exit(1);
});
