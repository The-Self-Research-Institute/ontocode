'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const zlib = require('zlib');

const swrlJar = require('../scripts/swrlJar');

function classFile(java) {
    const buf = Buffer.alloc(16);
    buf.writeUInt32BE(0xcafebabe, 0);
    buf.writeUInt16BE(java + 44, 6);
    return buf;
}

function makeZip(entries) {
    const locals = [];
    const centrals = [];
    let offset = 0;
    for (const { name, data, deflate } of entries) {
        const nameBytes = Buffer.from(name);
        const stored = deflate ? zlib.deflateRawSync(data) : data;
        const method = deflate ? 8 : 0;
        const local = Buffer.alloc(30);
        local.writeUInt32LE(0x04034b50, 0);
        local.writeUInt16LE(20, 4);
        local.writeUInt16LE(method, 8);
        local.writeUInt32LE(stored.length, 18);
        local.writeUInt32LE(data.length, 22);
        local.writeUInt16LE(nameBytes.length, 26);
        locals.push(local, nameBytes, stored);
        const central = Buffer.alloc(46);
        central.writeUInt32LE(0x02014b50, 0);
        central.writeUInt16LE(20, 4);
        central.writeUInt16LE(20, 6);
        central.writeUInt16LE(method, 10);
        central.writeUInt32LE(stored.length, 20);
        central.writeUInt32LE(data.length, 24);
        central.writeUInt16LE(nameBytes.length, 28);
        central.writeUInt32LE(offset, 42);
        centrals.push(central, nameBytes);
        offset += local.length + nameBytes.length + stored.length;
    }
    const directory = Buffer.concat(centrals);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0);
    end.writeUInt16LE(entries.length, 8);
    end.writeUInt16LE(entries.length, 10);
    end.writeUInt32LE(directory.length, 12);
    end.writeUInt32LE(offset, 16);
    return Buffer.concat([...locals, directory, end]);
}

function fatJar(libraries) {
    const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'onto-swrl-jar-')), 'swrl.jar');
    const entries = libraries.map(({ name, java, deflateClass }) => ({
        name: `BOOT-INF/lib/${name}`,
        data: makeZip([
            { name: 'META-INF/MANIFEST.MF', data: Buffer.from('Manifest-Version: 1.0') },
            { name: 'self/research/Example.class', data: classFile(java), deflate: deflateClass },
        ]),
    }));
    fs.writeFileSync(file, makeZip(entries));
    return file;
}

test('a shared library built for Java 21 inside the rule service jar is reported', () => {
    const jar = fatJar([{ name: 'common-utils-1.0.0.jar', java: 21 }, { name: 'common-models-1.0.0.jar', java: 17 }]);
    assert.deepEqual(swrlJar.findTooNew(jar, 17), [{ library: 'common-utils-1.0.0.jar', java: 21 }]);
});

test('a jar whose shared libraries are built for Java 17 passes', () => {
    const jar = fatJar([{ name: 'common-utils-1.0.0.jar', java: 17 }, { name: 'common-models-1.0.0.jar', java: 17 }]);
    assert.deepEqual(swrlJar.findTooNew(jar, 17), []);
    assert.equal(swrlJar.describeProblem(jar, 17), null);
});

test('libraries from other projects are not checked, only our own shared ones', () => {
    const jar = fatJar([{ name: 'spring-core-6.2.15.jar', java: 21 }, { name: 'common-utils-1.0.0.jar', java: 17 }]);
    assert.deepEqual(swrlJar.findTooNew(jar, 17), []);
});

test('compressed class files are read as well as stored ones', () => {
    const jar = fatJar([{ name: 'common-utils-1.0.0.jar', java: 21, deflateClass: true }]);
    assert.deepEqual(swrlJar.findTooNew(jar, 17), [{ library: 'common-utils-1.0.0.jar', java: 21 }]);
});

test('the message names the library, the Java versions and how to rebuild', () => {
    const jar = fatJar([{ name: 'common-utils-1.0.0.jar', java: 21 }]);
    const message = swrlJar.describeProblem(jar, 17);
    assert.match(message, /common-utils-1\.0\.0\.jar \(Java 21\)/);
    assert.match(message, /Java 17/);
    assert.match(message, /-Djava\.version=17/);
});

test('a file that is not a jar is refused with a clear error', () => {
    const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'onto-swrl-jar-')), 'broken.jar');
    fs.writeFileSync(file, 'this is not a zip');
    assert.throws(() => swrlJar.findTooNew(file, 17), /not a zip/);
});
