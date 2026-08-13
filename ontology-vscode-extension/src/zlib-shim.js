

const constants = {
  Z_NO_FLUSH: 0,
  Z_PARTIAL_FLUSH: 1,
  Z_SYNC_FLUSH: 2,
  Z_FULL_FLUSH: 3,
  Z_FINISH: 4,
  Z_BLOCK: 5,
  Z_TREES: 6,
  Z_OK: 0,
  Z_STREAM_END: 1,
  Z_NEED_DICT: 2,
  Z_ERRNO: -1,
  Z_STREAM_ERROR: -2,
  Z_DATA_ERROR: -3,
  Z_MEM_ERROR: -4,
  Z_BUF_ERROR: -5,
  Z_VERSION_ERROR: -6,
  Z_NO_COMPRESSION: 0,
  Z_BEST_SPEED: 1,
  Z_BEST_COMPRESSION: 9,
  Z_DEFAULT_COMPRESSION: -1,
  Z_FILTERED: 1,
  Z_HUFFMAN_ONLY: 2,
  Z_RLE: 3,
  Z_FIXED: 4,
  Z_DEFAULT_STRATEGY: 0,
  Z_BINARY: 0,
  Z_TEXT: 1,
  Z_ASCII: 1,
  Z_UNKNOWN: 2,
  Z_DEFLATED: 8
};

const noop = () => {};
const streamStub = {
  on: noop,
  pipe: () => streamStub,
  write: noop,
  end: noop,
  once: noop,
  emit: noop,
  removeListener: noop
};

module.exports = {
  ...constants,
  constants: constants,

  deflate: (data, options, callback) => {
    if (typeof options === 'function') {
      callback = options;
    }
    if (callback) {callback(null, Buffer.alloc(0));}
  },
  inflate: (data, options, callback) => {
    if (typeof options === 'function') {
      callback = options;
    }
    if (callback) {callback(null, Buffer.alloc(0));}
  },
  gzip: (data, options, callback) => {
    if (typeof options === 'function') {
      callback = options;
    }
    if (callback) {callback(null, Buffer.alloc(0));}
  },
  gunzip: (data, options, callback) => {
    if (typeof options === 'function') {
      callback = options;
    }
    if (callback) {callback(null, Buffer.alloc(0));}
  },
  deflateRaw: (data, options, callback) => {
    if (typeof options === 'function') {
      callback = options;
    }
    if (callback) {callback(null, Buffer.alloc(0));}
  },
  inflateRaw: (data, options, callback) => {
    if (typeof options === 'function') {
      callback = options;
    }
    if (callback) {callback(null, Buffer.alloc(0));}
  },
  unzip: (data, options, callback) => {
    if (typeof options === 'function') {
      callback = options;
    }
    if (callback) {callback(null, Buffer.alloc(0));}
  },

  deflateSync: () => Buffer.alloc(0),
  inflateSync: () => Buffer.alloc(0),
  gzipSync: () => Buffer.alloc(0),
  gunzipSync: () => Buffer.alloc(0),
  deflateRawSync: () => Buffer.alloc(0),
  inflateRawSync: () => Buffer.alloc(0),
  unzipSync: () => Buffer.alloc(0),

  createDeflate: () => streamStub,
  createInflate: () => streamStub,
  createGzip: () => streamStub,
  createGunzip: () => streamStub,
  createDeflateRaw: () => streamStub,
  createInflateRaw: () => streamStub,
  createUnzip: () => streamStub,

  Deflate: function() { return streamStub; },
  Inflate: function() { return streamStub; },
  Gzip: function() { return streamStub; },
  Gunzip: function() { return streamStub; },
  DeflateRaw: function() { return streamStub; },
  InflateRaw: function() { return streamStub; },
  Unzip: function() { return streamStub; }
};
