'use strict';

const fs = require('fs');
const path = require('path');

const MB = 1024 * 1024;
const GB = 1024 * MB;

const BUDGET_SHARE = 0.5;
const BACKEND_SHARE = 0.6;
const SWRL_HEAP_MB = 512;
const BACKEND_FLOOR_MB = 1280;
const BACKEND_CAP_MB = 12288;
const FUSEKI_FLOOR_MB = 768;
const FUSEKI_CAP_MB = 8192;
const MONGO_CACHE_MIN_MB = 512;
const MONGO_CACHE_MAX_MB = 1024;
const STEP_MB = 64;

const LOW_DISK_BYTES = 2 * GB;
const MIN_DISK_BYTES = 256 * MB;

function clamp(value, min, max) {
    return Math.min(Math.max(value, min), max);
}

function floorTo(value, step) {
    return Math.floor(value / step) * step;
}

function computeBudget(totalBytes) {
    const totalMb = totalBytes / MB;
    const budgetMb = totalMb * BUDGET_SHARE;
    const mongoCacheMb = clamp(Math.round((totalMb * 0.06) / 256) * 256, MONGO_CACHE_MIN_MB, MONGO_CACHE_MAX_MB);
    const remaining = Math.max(budgetMb - mongoCacheMb - SWRL_HEAP_MB, 0);
    const backendMb = clamp(floorTo(remaining * BACKEND_SHARE, STEP_MB), BACKEND_FLOOR_MB, BACKEND_CAP_MB);
    const fusekiMb = clamp(floorTo(remaining * (1 - BACKEND_SHARE), STEP_MB), FUSEKI_FLOOR_MB, FUSEKI_CAP_MB);
    return {
        totalMb: Math.round(totalMb),
        backendMb,
        fusekiMb,
        swrlMb: SWRL_HEAP_MB,
        mongoCacheMb,
        sumMb: backendMb + fusekiMb + SWRL_HEAP_MB + mongoCacheMb,
    };
}

function mongoCacheArg(budget) {
    return String(budget.mongoCacheMb / 1024);
}

function jvmSafetyFlags({ crashDir, tmpDir, heapDump = false }) {
    const flags = [
        '-XX:+ExitOnOutOfMemoryError',
        `-XX:ErrorFile=${path.join(crashDir, 'hs_err_pid%p.log')}`,
        `-Djava.io.tmpdir=${tmpDir}`,
    ];
    if (heapDump) {
        flags.push('-XX:+HeapDumpOnOutOfMemoryError', `-XX:HeapDumpPath=${crashDir}`);
    }
    return flags;
}

function freeBytes(dir, fsImpl = fs) {
    try {
        const s = fsImpl.statfsSync(dir);
        return Number(s.bavail) * Number(s.bsize);
    } catch (_) {
        return null;
    }
}

function diskStatus(dir, fsImpl = fs) {
    const free = freeBytes(dir, fsImpl);
    if (free === null) return { level: 'unknown', freeBytes: null };
    if (free < MIN_DISK_BYTES) return { level: 'critical', freeBytes: free };
    if (free < LOW_DISK_BYTES) return { level: 'low', freeBytes: free };
    return { level: 'ok', freeBytes: free };
}

function diskMessage(status) {
    const freeMb = Math.max(Math.round((status.freeBytes || 0) / MB), 0);
    if (status.level === 'critical') {
        return `This computer has only ${freeMb} MB of free disk space left. OntoCode Studio cannot start safely. Free some space and open it again.`;
    }
    if (status.level === 'low') {
        return `This computer is running low on disk space (${freeMb} MB free). Free at least 2 GB so your ontologies and databases keep saving correctly.`;
    }
    return '';
}

const RECOVERY_TEXT = {
    restart: ' OntoCode Studio is restarting it now.',
    lazy: ' It will start again the next time it is needed.',
    none: ' It stopped more than twice, so please close and reopen OntoCode Studio.',
};

function classifyExit({ name, code, outputTail = [], stopping, recovery = 'lazy' }) {
    if (stopping) return null;
    if (code === 0) return null;
    const text = outputTail.join('\n');
    const outOfMemory = code === 3 || /OutOfMemoryError/.test(text);
    const label = {
        MongoDB: 'The database',
        Desktop: 'The OntoCode backend',
        Fuseki: 'The graph and SPARQL service',
        SWRL: 'The rule reasoner',
    }[name] || name;
    const message = outOfMemory
        ? `${label} stopped because it ran out of memory. Close other large programs or work with a smaller ontology, then try again.`
        : `${label} stopped unexpectedly.`;
    return { name, code, outOfMemory, message: message + (RECOVERY_TEXT[recovery] || '') };
}

class RestartBudget {
    constructor({ max = 2, windowMs = 10 * 60 * 1000, now = Date.now } = {}) {
        this.max = max;
        this.windowMs = windowMs;
        this.now = now;
        this.stamps = [];
    }

    tryTake() {
        const t = this.now();
        this.stamps = this.stamps.filter((s) => t - s < this.windowMs);
        if (this.stamps.length >= this.max) return false;
        this.stamps.push(t);
        return true;
    }
}

class IdleTracker {
    constructor({ idleMs, now = Date.now }) {
        this.idleMs = idleMs;
        this.now = now;
        this.last = new Map();
        this.inflight = 0;
    }

    track(name) {
        this.last.set(name, this.now());
    }

    untrack(name) {
        this.last.delete(name);
    }

    touch(name) {
        if (this.last.has(name)) this.last.set(name, this.now());
    }

    begin() {
        this.inflight++;
    }

    end() {
        this.inflight = Math.max(this.inflight - 1, 0);
    }

    due() {
        if (!(this.idleMs > 0) || this.inflight > 0) return [];
        const t = this.now();
        const out = [];
        for (const [name, at] of this.last) {
            if (t - at >= this.idleMs) out.push(name);
        }
        return out;
    }
}

function idleMsFromEnv(env = process.env) {
    const raw = env.ONTOCODE_IDLE_STOP_MINUTES;
    if (raw === undefined || raw === '') return 30 * 60 * 1000;
    const minutes = Number(raw);
    if (!Number.isFinite(minutes) || minutes < 0) return 30 * 60 * 1000;
    return minutes * 60 * 1000;
}

module.exports = {
    MB, GB, LOW_DISK_BYTES, MIN_DISK_BYTES,
    computeBudget, mongoCacheArg, jvmSafetyFlags,
    freeBytes, diskStatus, diskMessage,
    classifyExit, RestartBudget, IdleTracker, idleMsFromEnv,
};
