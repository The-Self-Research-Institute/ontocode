
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const BUILD_ASSETS = path.join(__dirname, '..', 'build-assets');
const ICON = path.join(BUILD_ASSETS, 'icon.png');
const ICON512 = path.join(BUILD_ASSETS, 'icon-512.png');

function pngSize(filePath) {
    const b = fs.readFileSync(filePath);
    if (b.length < 24 || b.toString('ascii', 1, 4) !== 'PNG') {
        throw new Error(`Not a PNG: ${filePath}`);
    }
    return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
}

function ensure512() {
    if (!fs.existsSync(ICON)) {
        throw new Error(`Missing ${ICON}`);
    }
    const src = pngSize(ICON);
    if (fs.existsSync(ICON512)) {
        const existing = pngSize(ICON512);
        if (existing.w >= 512 && existing.h >= 512) {
            console.log(`  ✓  ${path.relative(process.cwd(), ICON512)} (${existing.w}x${existing.h})`);
            return;
        }
    }

    if (src.w >= 512 && src.h >= 512) {
        fs.copyFileSync(ICON, ICON512);
        console.log(`  ✓  Copied ${path.basename(ICON)} → icon-512.png`);
        return;
    }

    console.log(`  →  Upscale icon.png (${src.w}x${src.h}) → icon-512.png`);
    if (process.platform === 'darwin') {
        execSync(`sips -z 512 512 "${ICON}" --out "${ICON512}"`, { stdio: 'inherit' });
    } else {
        execSync(
            `npx --yes sharp-cli resize 512 512 "${ICON}" -o "${ICON512}"`,
            { stdio: 'inherit', shell: true, cwd: path.join(__dirname, '..') },
        );
    }

    const out = pngSize(ICON512);
    if (out.w < 512 || out.h < 512) {
        throw new Error(`icon-512.png is ${out.w}x${out.h}; expected at least 512x512`);
    }
    console.log(`  ✓  icon-512.png (${out.w}x${out.h})`);
}

if (require.main === module) {
    ensure512();
}

module.exports = { ensure512, ICON512 };
