'use strict';

const fs = require('fs');
const path = require('path');

const VC_RUNTIME_DLLS = ['vcruntime140.dll', 'vcruntime140_1.dll', 'msvcp140.dll'];
const VC_RUNTIME_DLLS_ARM64 = ['vcruntime140.dll'];

function requiredFor(arch) {
    return arch === 'arm64' ? VC_RUNTIME_DLLS_ARM64 : VC_RUNTIME_DLLS;
}

function bundleVcRuntime(binDir, sourceBinDirs, names = VC_RUNTIME_DLLS) {
    const copied = [];
    const present = [];
    const missing = [];
    for (const name of names) {
        const target = path.join(binDir, name);
        if (fs.existsSync(target)) {
            present.push(name);
            continue;
        }
        const source = sourceBinDirs.map((dir) => path.join(dir, name)).find((p) => fs.existsSync(p));
        if (!source) {
            missing.push(name);
            continue;
        }
        fs.copyFileSync(source, target);
        copied.push(name);
    }
    return { copied, present, missing };
}

function missingVcRuntime(binDir, names = VC_RUNTIME_DLLS) {
    return names.filter((name) => !fs.existsSync(path.join(binDir, name)));
}

module.exports = { VC_RUNTIME_DLLS, VC_RUNTIME_DLLS_ARM64, requiredFor, bundleVcRuntime, missingVcRuntime };
