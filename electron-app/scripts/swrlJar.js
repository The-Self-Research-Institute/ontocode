'use strict';

const fs = require('fs');
const zlib = require('zlib');

const END_OF_DIRECTORY = 0x06054b50;
const DIRECTORY_ENTRY = 0x02014b50;
const CLASS_MAGIC = 0xcafebabe;
const JAVA_OFFSET = 44;

function readEntries(buf) {
    let end = -1;
    for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
        if (buf.readUInt32LE(i) === END_OF_DIRECTORY) {
            end = i;
            break;
        }
    }
    if (end < 0) throw new Error('not a zip or jar file');
    const count = buf.readUInt16LE(end + 10);
    let pos = buf.readUInt32LE(end + 16);
    const entries = [];
    for (let n = 0; n < count; n++) {
        if (buf.readUInt32LE(pos) !== DIRECTORY_ENTRY) throw new Error('damaged zip directory');
        const nameLength = buf.readUInt16LE(pos + 28);
        const extraLength = buf.readUInt16LE(pos + 30);
        const commentLength = buf.readUInt16LE(pos + 32);
        entries.push({
            method: buf.readUInt16LE(pos + 10),
            size: buf.readUInt32LE(pos + 20),
            offset: buf.readUInt32LE(pos + 42),
            name: buf.toString('utf8', pos + 46, pos + 46 + nameLength),
        });
        pos += 46 + nameLength + extraLength + commentLength;
    }
    return entries;
}

function entryBytes(buf, entry) {
    const start = entry.offset + 30 + buf.readUInt16LE(entry.offset + 26) + buf.readUInt16LE(entry.offset + 28);
    const raw = buf.subarray(start, start + entry.size);
    if (entry.method === 0) return raw;
    if (entry.method === 8) return zlib.inflateRawSync(raw);
    throw new Error(`unsupported zip method ${entry.method}`);
}

function javaVersionOfClass(bytes) {
    if (bytes.length < 8 || bytes.readUInt32BE(0) !== CLASS_MAGIC) throw new Error('not a class file');
    return bytes.readUInt16BE(6) - JAVA_OFFSET;
}

function highestJavaInJar(jarBuffer) {
    let highest = 0;
    for (const entry of readEntries(jarBuffer)) {
        if (!entry.name.endsWith('.class') || entry.name.endsWith('module-info.class')) continue;
        highest = Math.max(highest, javaVersionOfClass(entryBytes(jarBuffer, entry)));
        break;
    }
    return highest;
}

function findTooNew(jarPath, maxJava) {
    const outer = fs.readFileSync(jarPath);
    const tooNew = [];
    for (const entry of readEntries(outer)) {
        if (!/^BOOT-INF\/lib\/common-[^/]+\.jar$/.test(entry.name)) continue;
        const java = highestJavaInJar(entryBytes(outer, entry));
        if (java > maxJava) tooNew.push({ library: entry.name.replace('BOOT-INF/lib/', ''), java });
    }
    return tooNew;
}

function describeProblem(jarPath, maxJava) {
    const tooNew = findTooNew(jarPath, maxJava);
    if (!tooNew.length) return null;
    const list = tooNew.map((t) => `${t.library} (Java ${t.java})`).join(', ');
    return `${jarPath} contains code built for a newer Java than ${maxJava}: ${list}. `
        + `The rule service runs on Java ${maxJava} and would crash at startup. `
        + `Rebuild it with: mvn -pl ontology-swrl -am clean package -DskipTests `
        + `-Djava.version=${maxJava} -Dmaven.compiler.release=${maxJava}`;
}

module.exports = { readEntries, entryBytes, javaVersionOfClass, highestJavaInJar, findTooNew, describeProblem };
