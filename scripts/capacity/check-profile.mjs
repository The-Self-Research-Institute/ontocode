import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const argv = process.argv.slice(2);
const flag = name => argv.includes(name);
const profileName = argv.find(a => !a.startsWith('--')) || 'r6i-xlarge';
const STRICT = flag('--strict');
const JSON_OUT = flag('--json');

const profilePath = path.join(ROOT, 'capacity', 'profiles', profileName + '.json');
if (!fs.existsSync(profilePath)) {
    console.error('Profile not found: ' + profilePath);
    process.exit(2);
}
const profile = JSON.parse(fs.readFileSync(profilePath, 'utf8'));

function toMb(value, unit) {
    const n = parseFloat(value);
    const u = String(unit).toLowerCase();
    if (u === 'g') return Math.round(n * 1024);
    if (u === 'm') return Math.round(n);
    if (u === 'k') return Math.round(n / 1024);
    return Math.round(n / (1024 * 1024));
}

function readCompose(files) {
    const found = {};
    for (const rel of files) {
        const full = path.join(ROOT, rel);
        if (!fs.existsSync(full)) {
            found.__missing = (found.__missing || []).concat(rel);
            continue;
        }
        const lines = fs.readFileSync(full, 'utf8').split(/\r?\n/);
        let inServices = false;
        let service = null;
        let inLimits = false;
        for (const line of lines) {
            if (/^services:\s*$/.test(line)) { inServices = true; continue; }
            if (/^[a-z]/.test(line) && !/^services:/.test(line)) { inServices = false; service = null; continue; }
            if (!inServices) continue;
            const s = /^  ([A-Za-z0-9_-]+):\s*$/.exec(line);
            if (s) { service = s[1]; inLimits = false; found[service] = found[service] || {}; continue; }
            if (!service) continue;
            if (/^\s+limits:\s*$/.test(line)) { inLimits = true; continue; }
            if (/^\s+reservations:\s*$/.test(line)) { inLimits = false; continue; }
            const mem = /^\s+memory:\s*"?([0-9.]+)([mMgGkK])b?"?\s*$/.exec(line);
            if (mem && inLimits) found[service].limitMb = toMb(mem[1], mem[2]);
            const memLimit = /^\s+mem_limit:\s*"?([0-9.]+)([mMgGkK])b?"?\s*$/.exec(line);
            if (memLimit) found[service].limitMb = toMb(memLimit[1], memLimit[2]);
            const xmx = /-Xmx([0-9.]+)([mMgGkK])/.exec(line);
            if (xmx) found[service].heapMb = toMb(xmx[1], xmx[2]);
        }
    }
    return found;
}

const compose = readCompose(profile.composeFiles);
const results = [];
const add = (level, rule, message) => results.push({ level, rule, message });
const mb = n => n.toLocaleString('en-US') + ' MB';

const services = Object.entries(profile.services);
const rules = profile.rules;

const hostRam = profile.host.ramMb;
let limitSum = 0;
for (const [name, cfg] of services) {
    limitSum += cfg.limitMb;
}
const pg = profile.database;

const sumFraction = limitSum / hostRam;
if (sumFraction > rules.maxLimitSumFraction) {
    add('FAIL', 'limits-fit-host', 'Container limits add up to ' + mb(limitSum) + ' = ' + (sumFraction * 100).toFixed(1) + '% of the ' + mb(hostRam) + ' host. The rule allows ' + (rules.maxLimitSumFraction * 100) + '% so the operating system, page cache and Docker keep room.');
} else {
    add('PASS', 'limits-fit-host', 'Container limits add up to ' + mb(limitSum) + ' = ' + (sumFraction * 100).toFixed(1) + '% of the host.');
}

for (const [name, cfg] of services) {
    if (cfg.heapMb == null) continue;
    if (cfg.heapMb > cfg.limitMb) {
        add('FAIL', 'heap-below-limit', name + ': heap ' + mb(cfg.heapMb) + ' is above its container limit ' + mb(cfg.limitMb) + '. The container will be killed before Java can free memory.');
        continue;
    }
    const needed = rules.jvmNativeBaseMb + Math.round(cfg.heapMb * rules.jvmNativeHeapFraction) + (cfg.extraNativeMb || 0);
    const headroom = cfg.limitMb - cfg.heapMb;
    if (headroom < needed) {
        add('FAIL', 'jvm-headroom', name + ': only ' + mb(headroom) + ' left for thread stacks, metaspace and direct buffers outside the heap, rule needs ' + mb(needed) + '.');
    } else {
        add('PASS', 'jvm-headroom', name + ': ' + mb(headroom) + ' outside the heap (needs ' + mb(needed) + ').');
    }
}

if (pg) {
    const pools = Object.values(pg.pools).reduce((a, b) => a + b, 0);
    const budget = Math.floor((pg.maxConnections - pg.reservedConnections) * rules.maxDbConnectionFraction);
    if (pools > budget) {
        add('FAIL', 'db-connections', 'Connection pools add up to ' + pools + ' but only ' + budget + ' of ' + pg.maxConnections + ' database connections are allowed after the reserve.');
    } else {
        add('PASS', 'db-connections', 'Connection pools add up to ' + pools + ', within ' + budget + ' allowed of ' + pg.maxConnections + '.');
    }
    if (pg.sharedBuffersMb > pg.limitMb * 0.4) {
        add('WARN', 'pg-buffers', 'shared_buffers ' + mb(pg.sharedBuffersMb) + ' is more than 40% of the database container limit ' + mb(pg.limitMb) + '.');
    }
}

const items = profile.disk.items;
const fixedGb = items.filter(i => i.capGb != null).reduce((a, i) => a + i.capGb, 0);
if (fixedGb > profile.host.diskGb * rules.maxFixedDiskFraction) {
    add('FAIL', 'disk-fixed-caps', 'Capped items (logs, dumps) can use ' + fixedGb + ' GB, more than ' + (rules.maxFixedDiskFraction * 100) + '% of the ' + profile.host.diskGb + ' GB disk.');
} else {
    add('PASS', 'disk-fixed-caps', 'Capped items (logs, dumps) can use ' + fixedGb + ' GB of the ' + profile.host.diskGb + ' GB disk.');
}
for (const item of items.filter(i => i.capGb == null)) {
    add('WARN', 'disk-unbounded', item.name + ': no cap' + (item.note ? ' (' + item.note + ')' : '') + '.');
}

const drift = [];
if (compose.__missing) add('WARN', 'compose-missing', 'Compose files not found: ' + compose.__missing.join(', '));
for (const [name, cfg] of services) {
    const actual = compose[name];
    if (!actual) {
        add('FAIL', 'compose-drift', name + ': service not found in ' + profile.composeFiles.join(' + ') + '.');
        continue;
    }
    if (actual.limitMb !== undefined && actual.limitMb !== cfg.limitMb) {
        drift.push(name + ' limit: profile ' + mb(cfg.limitMb) + ', compose ' + mb(actual.limitMb));
    }
    if (cfg.heapMb != null && actual.heapMb !== undefined && actual.heapMb !== cfg.heapMb) {
        drift.push(name + ' heap: profile ' + mb(cfg.heapMb) + ', compose ' + mb(actual.heapMb));
    }
    if (actual.limitMb === undefined) drift.push(name + ': no memory limit found in compose');
    if (cfg.heapMb != null && actual.heapMb === undefined) drift.push(name + ': no -Xmx found in compose');
}
for (const svc of Object.keys(compose)) {
    if (svc === '__missing') continue;
    if (!profile.services[svc] && !['plugin-init'].includes(svc)) {
        drift.push(svc + ': in compose but not in the profile');
    }
}
if (drift.length) {
    for (const d of drift) add('FAIL', 'compose-drift', d);
} else {
    add('PASS', 'compose-drift', 'Profile matches the compose files for every service.');
}

const counts = { PASS: 0, WARN: 0, FAIL: 0 };
for (const r of results) counts[r.level]++;

if (JSON_OUT) {
    console.log(JSON.stringify({ profile: profile.name, limitSumMb: limitSum, hostRamMb: hostRam, counts, results }, null, 2));
} else {
    console.log('Profile: ' + profile.name);
    console.log('Host: ' + mb(hostRam) + ' RAM, ' + profile.host.cpus + ' CPUs, ' + profile.host.diskGb + ' GB disk');
    console.log('');
    for (const level of ['FAIL', 'WARN', 'PASS']) {
        for (const r of results.filter(x => x.level === level)) {
            console.log(level.padEnd(5) + ' [' + r.rule + '] ' + r.message);
        }
    }
    console.log('');
    console.log('Result: ' + counts.FAIL + ' fail, ' + counts.WARN + ' warn, ' + counts.PASS + ' pass');
}

process.exit(counts.FAIL > 0 || (STRICT && counts.WARN > 0) ? 1 : 0);
