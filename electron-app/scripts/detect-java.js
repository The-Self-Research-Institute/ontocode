

const { execFileSync } = require('child_process');
const path   = require('path');
const fs     = require('fs');
const { app } = require('electron');

const RESOURCES_DIR = app.isPackaged
    ? path.join(process.resourcesPath, 'backend')
    : path.join(__dirname, '..', 'resources', 'backend');

function candidateBins() {
    const bins = [];

    const bundled = path.join(RESOURCES_DIR, 'jre', 'bin',
        process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(bundled)) bins.push(bundled);

    if (process.env.JAVA_HOME) {
        const jhBin = path.join(process.env.JAVA_HOME, 'bin',
            process.platform === 'win32' ? 'java.exe' : 'java');
        if (fs.existsSync(jhBin)) bins.push(jhBin);
    }

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

        const base = '/Library/Java/JavaVirtualMachines';
        if (fs.existsSync(base)) {
            fs.readdirSync(base).forEach(d => {
                bins.push(path.join(base, d, 'Contents', 'Home', 'bin', 'java'));
            });
        }
    } else {

        ['/usr/bin/java', '/usr/local/bin/java', '/opt/java/bin/java'].forEach(p => bins.push(p));
    }

    bins.push('java');

    return bins;
}

function check() {
    const candidates = candidateBins();

    for (const bin of candidates) {
        try {

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
