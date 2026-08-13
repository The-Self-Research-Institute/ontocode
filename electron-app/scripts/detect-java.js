/**
 * detect-java.js
 *
 * Checks for a Java 17+ runtime.  Returns the version string on success,
 * false on failure.
 *
 * Search order:
 *  1. Bundled JRE inside resources/backend/jre/
 *  2. JAVA_HOME environment variable
 *  3. `java` on PATH
 */

const { execFileSync } = require('child_process');
const path   = require('path');
const fs     = require('fs');
const { app } = require('electron');

const RESOURCES_DIR = app.isPackaged
    ? path.join(process.resourcesPath, 'backend')
    : path.join(__dirname, '..', 'resources', 'backend');

function candidateBins() {
    const bins = [];

    // 1. Bundled JRE
    const bundled = path.join(RESOURCES_DIR, 'jre', 'bin',
        process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(bundled)) bins.push(bundled);

    // 2. JAVA_HOME
    if (process.env.JAVA_HOME) {
        const jhBin = path.join(process.env.JAVA_HOME, 'bin',
            process.platform === 'win32' ? 'java.exe' : 'java');
        if (fs.existsSync(jhBin)) bins.push(jhBin);
    }

    // 3. Well-known install locations
    if (process.platform === 'win32') {
        const roots = [
            'C:\\Program Files\\Java',
            'C:\\Program Files\\Eclipse Adoptium',
            'C:\\Program Files\\Microsoft',
        ];
        roots.forEach((root) => {
            if (!fs.existsSync(root)) return;
            fs.readdirSync(root)
                .filter(d => d.match(/jdk|jre/i))
                .forEach(d => bins.push(path.join(root, d, 'bin', 'java.exe')));
        });
    } else if (process.platform === 'darwin') {
        // macOS: /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java
        const base = '/Library/Java/JavaVirtualMachines';
        if (fs.existsSync(base)) {
            fs.readdirSync(base).forEach(d => {
                bins.push(path.join(base, d, 'Contents', 'Home', 'bin', 'java'));
            });
        }
    } else {
        // Linux
        ['/usr/bin/java', '/usr/local/bin/java', '/opt/java/bin/java'].forEach(p => bins.push(p));
    }

    // 4. PATH fallback
    bins.push('java');

    return bins;
}

/**
 * Returns the version string (e.g. "17.0.9") if Java ≥ 17 is found,
 * or false otherwise.
 */
function check() {
    const candidates = candidateBins();

    for (const bin of candidates) {
        try {
            // `java -version` prints to stderr; `java --version` (Java 9+) prints to stdout.
            // Timeout is generous (not a fixed wait — returns as soon as the process exits)
            // because the bundled JRE's first read can be slow when it lives inside a
            // FUSE-mounted AppImage (squashfuse) sitting on a slow filesystem (e.g. a
            // Windows drive mounted into WSL) — that cold read alone has been observed
            // to take ~7s, which a 5s timeout misreports as "Java not found".
            const out = execFileSync(bin, ['--version'], {
                timeout: 20000,
                encoding: 'utf8',
                windowsHide: true,
            });
            const match = out.match(/(\d+)(?:\.(\d+))?(?:\.(\d+))?/);
            if (match) {
                const major = parseInt(match[1], 10);
                if (major >= 17) {
                    return `${major}${match[2] ? '.' + match[2] : ''}${match[3] ? '.' + match[3] : ''}`;
                }
            }
        } catch (_) {
            // Try next candidate
        }
    }

    return false;
}

module.exports = { check };
