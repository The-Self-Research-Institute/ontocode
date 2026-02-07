// Web extension entry point - ensures zlib shim is loaded BEFORE axios
// This prevents "Cannot read properties of undefined (reading 'Z_SYNC_FLUSH')" errors

// Pre-load zlib shim with all constants
const zlib = require('./zlib-shim.js');

// Make it globally available so axios can find it if needed
if (typeof globalThis !== 'undefined') {
    (globalThis as any).zlib = zlib;
}

// Now load the main extension
export * from './extension';
