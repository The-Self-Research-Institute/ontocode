const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { Pool } = require('pg');

const DEFAULT_URL = 'jdbc:postgresql://localhost:5432/ontocode';

function connectionConfig(env = process.env) {
  const raw = (env.ONTOCODE_DB_URL || DEFAULT_URL).replace(/^jdbc:/, '');
  const url = new URL(raw);
  const config = {
    host: url.hostname,
    port: url.port ? parseInt(url.port, 10) : 5432,
    database: decodeURIComponent(url.pathname.replace(/^\//, '')) || 'ontocode',
    user: env.ONTOCODE_DB_USER || decodeURIComponent(url.username) || 'ontocode',
    max: parseInt(env.ONTOCODE_DB_POOL_MAX || '2', 10),
    connectionTimeoutMillis: 5000,
  };
  const password = env.ONTOCODE_DB_PASSWORD || decodeURIComponent(url.password);
  if (password) config.password = password;
  return config;
}

function newPool(env = process.env) {
  return new Pool(connectionConfig(env));
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function waitForSchema(pool, requiredTable, options = {}) {
  const attempts = options.attempts || 60;
  const delayMs = options.delayMs || 5000;
  const log = options.log || (() => {});
  for (let attempt = 1; attempt <= attempts; attempt++) {
    try {
      const result = await pool.query('select to_regclass($1) as t', ['public.' + requiredTable]);
      if (result.rows[0].t) return;
      log('Table ' + requiredTable + ' does not exist yet, attempt ' + attempt + ' of ' + attempts);
    } catch (error) {
      log('PostgreSQL not ready yet, attempt ' + attempt + ' of ' + attempts + ': ' + error.message);
    }
    await sleep(delayMs);
  }
  throw new Error('Table ' + requiredTable + ' was not available after ' + attempts + ' attempts');
}

let counter = crypto.randomInt(0, 0x1000000);
const processUnique = crypto.randomBytes(5);

function newObjectId() {
  const bytes = Buffer.alloc(12);
  bytes.writeUInt32BE(Math.floor(Date.now() / 1000), 0);
  processUnique.copy(bytes, 4);
  counter = (counter + 1) & 0xffffff;
  bytes[9] = (counter >> 16) & 0xff;
  bytes[10] = (counter >> 8) & 0xff;
  bytes[11] = counter & 0xff;
  return bytes.toString('hex');
}

function blobsRoot(env = process.env) {
  return path.resolve(env.ONTOCODE_BLOBS_DIR || './data/blobs');
}

function blobPath(root, bucket, id) {
  if (!/^[A-Za-z0-9._-]+$/.test(bucket) || !/^[A-Za-z0-9]+$/.test(id)) {
    throw new Error('Unsafe bucket or id');
  }
  const a = id.length >= 2 ? id.substring(0, 2) : '00';
  const b = id.length >= 4 ? id.substring(2, 4) : '00';
  return path.join(root, bucket, a, b, id);
}

function sha256File(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    fs.createReadStream(file).on('error', reject).on('data', d => hash.update(d)).on('end', () => resolve(hash.digest('hex')));
  });
}

async function storeFile(pool, root, { bucket, filename, contentType, metadata, sourcePath }) {
  const id = newObjectId();
  const tempDir = path.join(root, '.tmp');
  const temp = path.join(tempDir, id + '.part');
  const target = blobPath(root, bucket, id);
  fs.mkdirSync(tempDir, { recursive: true });
  fs.copyFileSync(sourcePath, temp);
  const sha256 = await sha256File(temp);
  const length = fs.statSync(temp).size;
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.renameSync(temp, target);
  try {
    await pool.query(
      'insert into stored_file(id, bucket, filename, content_type, length, sha256, upload_date, metadata, storage) ' +
      "values ($1, $2, $3, $4, $5, $6, now(), $7::jsonb, 'disk')",
      [id, bucket, filename, contentType, length, sha256, JSON.stringify(metadata || {})]
    );
  } catch (error) {
    fs.rmSync(target, { force: true });
    throw error;
  }
  return { id, length, sha256 };
}

async function deleteStoredFile(pool, root, id) {
  const found = await pool.query('select bucket, storage from stored_file where id = $1', [id]);
  if (found.rowCount === 0) return false;
  await pool.query('delete from stored_file where id = $1', [id]);
  if (found.rows[0].storage === 'disk') {
    fs.rmSync(blobPath(root, found.rows[0].bucket, id), { force: true });
  }
  return true;
}

module.exports = { connectionConfig, newPool, waitForSchema, newObjectId, blobsRoot, blobPath, storeFile, deleteStoredFile };
