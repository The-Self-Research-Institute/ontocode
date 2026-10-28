'use strict';

const fs = require('fs');
const path = require('path');
const childProcess = require('child_process');

function defaultRun(cmd, args) {
    const r = childProcess.spawnSync(cmd, args, { encoding: 'utf8', windowsHide: true, timeout: 8000 });
    return { status: r.status, stdout: r.stdout || '' };
}

function createProcessGuard({
    registryFile,
    marker,
    platform = process.platform,
    run = defaultRun,
    fsImpl = fs,
    kill = (pid, sig) => process.kill(pid, sig),
}) {
    const live = new Map();

    function readRegistry() {
        try {
            const parsed = JSON.parse(fsImpl.readFileSync(registryFile, 'utf8'));
            return Array.isArray(parsed.entries) ? parsed.entries : [];
        } catch (_) {
            return [];
        }
    }

    function writeRegistry(entries) {
        try {
            fsImpl.mkdirSync(path.dirname(registryFile), { recursive: true });
            const tmp = `${registryFile}.tmp`;
            fsImpl.writeFileSync(tmp, JSON.stringify({ entries }), 'utf8');
            fsImpl.renameSync(tmp, registryFile);
        } catch (_) {}
    }

    function imageName(pid) {
        if (platform === 'win32') {
            const r = run('tasklist', ['/FI', `PID eq ${pid}`, '/FO', 'CSV', '/NH']);
            const m = /^"([^"]+)","(\d+)"/m.exec(r.stdout);
            return m && Number(m[2]) === pid ? m[1] : null;
        }
        const r = run('ps', ['-p', String(pid), '-o', 'comm=']);
        const out = r.stdout.trim();
        return out ? path.basename(out) : null;
    }

    function commandLine(pid) {
        if (platform === 'win32') {
            const r = run('powershell', [
                '-NoProfile', '-NonInteractive', '-Command',
                `(Get-CimInstance Win32_Process -Filter 'ProcessId=${pid}').CommandLine`,
            ]);
            return r.stdout.trim() || null;
        }
        const r = run('ps', ['-p', String(pid), '-o', 'args=']);
        return r.stdout.trim() || null;
    }

    function killTree(pid) {
        if (!Number.isInteger(pid) || pid <= 0) return false;
        if (platform === 'win32') {
            const r = run('taskkill', ['/PID', String(pid), '/T', '/F']);
            return r.status === 0;
        }
        try {
            kill(pid, 'SIGKILL');
            return true;
        } catch (_) {
            return false;
        }
    }

    function register(name, exeName, child) {
        if (!child || !child.pid) return;
        const pid = child.pid;
        live.set(pid, name);
        const entries = readRegistry().filter((e) => e.pid !== pid);
        entries.push({ pid, name, exe: exeName, startedAt: Date.now() });
        writeRegistry(entries);
        child.once('exit', () => unregister(pid));
    }

    function unregister(pid) {
        live.delete(pid);
        writeRegistry(readRegistry().filter((e) => e.pid !== pid));
    }

    function killAllSync() {
        const killed = [];
        for (const pid of Array.from(live.keys())) {
            killTree(pid);
            killed.push(pid);
        }
        live.clear();
        writeRegistry([]);
        return killed;
    }

    function reapStale() {
        const reaped = [];
        const skipped = [];
        for (const e of readRegistry()) {
            if (!Number.isInteger(e.pid) || !e.exe) continue;
            if (live.has(e.pid)) continue;
            const image = imageName(e.pid);
            if (!image) continue;
            if (image.toLowerCase() !== String(e.exe).toLowerCase()) {
                skipped.push({ pid: e.pid, reason: 'different program' });
                continue;
            }
            const cmd = commandLine(e.pid);
            if (!cmd || !cmd.toLowerCase().includes(String(marker).toLowerCase())) {
                skipped.push({ pid: e.pid, reason: 'not started by OntoCode' });
                continue;
            }
            if (killTree(e.pid)) reaped.push({ pid: e.pid, name: e.name });
        }
        writeRegistry(readRegistry().filter((e) => live.has(e.pid)));
        return { reaped, skipped };
    }

    return { register, unregister, killTree, killAllSync, reapStale, readRegistry, livePids: () => Array.from(live.keys()) };
}

module.exports = { createProcessGuard };
