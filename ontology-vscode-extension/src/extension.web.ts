

const zlib = require('./zlib-shim.js');

if (typeof globalThis !== 'undefined') {
    (globalThis as any).zlib = zlib;
}

export * from './extension';
