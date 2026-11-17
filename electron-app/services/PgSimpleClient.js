'use strict';

const net = require('net');
const crypto = require('crypto');

function hmac(key, data) {
    return crypto.createHmac('sha256', key).update(data).digest();
}

function sha256(data) {
    return crypto.createHash('sha256').update(data).digest();
}

function scramClientFinal({ password, clientFirstBare, serverFirst }) {
    const attrs = Object.fromEntries(serverFirst.split(',').map((part) => [part[0], part.slice(2)]));
    const salt = Buffer.from(attrs.s, 'base64');
    const iterations = parseInt(attrs.i, 10);
    const salted = crypto.pbkdf2Sync(Buffer.from(password, 'utf8'), salt, iterations, 32, 'sha256');
    const clientKey = hmac(salted, 'Client Key');
    const finalWithoutProof = `c=biws,r=${attrs.r}`;
    const authMessage = `${clientFirstBare},${serverFirst},${finalWithoutProof}`;
    const signature = hmac(sha256(clientKey), authMessage);
    const proof = Buffer.from(clientKey.map((byte, i) => byte ^ signature[i]));
    return {
        clientFinal: `${finalWithoutProof},p=${proof.toString('base64')}`,
        serverSignature: hmac(hmac(salted, 'Server Key'), authMessage).toString('base64'),
    };
}

function cstring(text) {
    return Buffer.from(`${text}\0`, 'utf8');
}

function int32(value) {
    const buf = Buffer.alloc(4);
    buf.writeInt32BE(value, 0);
    return buf;
}

function message(type, body) {
    return Buffer.concat([Buffer.from(type, 'latin1'), int32(body.length + 4), body]);
}

function startupMessage(user, database) {
    const body = Buffer.concat([cstring('user'), cstring(user), cstring('database'), cstring(database), Buffer.from([0])]);
    return Buffer.concat([int32(body.length + 8), int32(196608), body]);
}

function errorText(body) {
    let offset = 0;
    while (offset < body.length && body[offset] !== 0) {
        const field = String.fromCharCode(body[offset]);
        const end = body.indexOf(0, offset + 1);
        const value = body.subarray(offset + 1, end).toString('utf8');
        if (field === 'M') return value;
        offset = end + 1;
    }
    return 'the database reported an error';
}

function run({ host = '127.0.0.1', port, user, password, database, sql, timeoutMs = 30000 }) {
    return new Promise((resolve, reject) => {
        const sock = new net.Socket();
        const clientNonce = crypto.randomBytes(18).toString('base64');
        const clientFirstBare = `n=,r=${clientNonce}`;
        let pending = Buffer.alloc(0);
        let expectedServerSignature = null;
        let querySent = false;
        let queryError = null;
        let settled = false;

        const timer = setTimeout(() => finish(new Error(`the database did not answer within ${Math.round(timeoutMs / 1000)}s`)), timeoutMs);

        function finish(err) {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            sock.destroy();
            if (err) reject(err); else resolve();
        }

        function authenticate(code, body) {
            if (code === 0) return;
            if (code === 10) {
                const first = Buffer.from(`n,,${clientFirstBare}`);
                sock.write(message('p', Buffer.concat([cstring('SCRAM-SHA-256'), int32(first.length), first])));
            } else if (code === 11) {
                const result = scramClientFinal({ password, clientFirstBare, serverFirst: body.subarray(4).toString('utf8') });
                expectedServerSignature = result.serverSignature;
                sock.write(message('p', Buffer.from(result.clientFinal)));
            } else if (code === 12) {
                const finalText = body.subarray(4).toString('utf8');
                if (finalText !== `v=${expectedServerSignature}`) finish(new Error('the database failed the password check'));
            } else {
                finish(new Error(`the database asked for an unsupported login method (${code})`));
            }
        }

        function handle(type, body) {
            if (type === 'R') return authenticate(body.readInt32BE(0), body);
            if (type === 'E') {
                if (querySent) queryError = errorText(body);
                else finish(new Error(errorText(body)));
                return undefined;
            }
            if (type === 'Z') {
                if (!querySent) {
                    querySent = true;
                    sock.write(message('Q', cstring(sql)));
                } else {
                    sock.write(message('X', Buffer.alloc(0)));
                    finish(queryError ? new Error(queryError) : null);
                }
            }
            return undefined;
        }

        sock.on('error', (err) => finish(err));
        sock.on('close', () => finish(new Error('the database closed the connection')));
        sock.on('data', (chunk) => {
            pending = Buffer.concat([pending, chunk]);
            while (pending.length >= 5) {
                const length = pending.readInt32BE(1);
                if (pending.length < length + 1) break;
                const type = String.fromCharCode(pending[0]);
                const body = pending.subarray(5, length + 1);
                pending = pending.subarray(length + 1);
                handle(type, body);
            }
        });
        sock.connect(port, host, () => sock.write(startupMessage(user, database)));
    });
}

module.exports = { run, scramClientFinal, startupMessage, message };
