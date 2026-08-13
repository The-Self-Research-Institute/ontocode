

const http   = require('http');
const net    = require('net');
const svcMgr = require('./ServiceManager');

const DEFAULT_PROXY_PORT = 18085;
let PROXY_PORT   = DEFAULT_PROXY_PORT;
let DESKTOP_PORT = 18083;
let SWRL_PORT    = 18084;

let proxyServer = null;

function isPortFree(port) {
    return new Promise((resolve) => {
        const srv = net.createServer();
        srv.once('error', () => resolve(false));
        srv.once('listening', () => { srv.close(); resolve(true); });
        srv.listen(port, '127.0.0.1');
    });
}

async function findFreeProxyPort() {
    for (let p = DEFAULT_PROXY_PORT; p < DEFAULT_PROXY_PORT + 20; p++) {
        if (await isPortFree(p)) { PROXY_PORT = p; return; }
    }
    throw new Error(`No free proxy port found near ${DEFAULT_PROXY_PORT}`);
}

function targetPort(url) {
    if (url && url.startsWith('/api/swrl')) return SWRL_PORT;
    return DESKTOP_PORT;
}

function rewritePath(url) {
    if (url && url.startsWith('/plugin-service/api/reasoner/')) {
        return url.substring('/plugin-service'.length);
    }
    return url;
}

async function proxyHttp(req, res) {
    req.url = rewritePath(req.url);
    const port = targetPort(req.url);

    res.setHeader('Access-Control-Allow-Origin',  '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,PATCH,DELETE,OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type,Authorization');

    res.setHeader('Permissions-Policy', 'unload=(self)');

    if (req.method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
    }

    if (port === SWRL_PORT) {
        try {
            await svcMgr.ensureSwrl();
        } catch (err) {
            console.error(`[Proxy] Failed to start SWRL on demand: ${err.message}`);
            res.writeHead(503, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: `SWRL reasoner unavailable: ${err.message}` }));
            return;
        }
    }

    const options = {
        hostname: '127.0.0.1',
        port,
        path:    req.url,
        method:  req.method,
        headers: { ...req.headers, host: `127.0.0.1:${port}` },
    };

    const proxyReq = http.request(options, (proxyRes) => {
        const headers = { ...proxyRes.headers, 'access-control-allow-origin': '*' };
        res.writeHead(proxyRes.statusCode, headers);
        proxyRes.pipe(res, { end: true });
    });

    proxyReq.on('error', (err) => {
        console.error(`[Proxy] HTTP error → port ${port}: ${err.message}`);
        if (!res.headersSent) {
            res.writeHead(502, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: `Backend unavailable: ${err.message}` }));
        }
    });

    req.pipe(proxyReq, { end: true });
}

function proxyWs(req, socket, head) {
    const port = targetPort(req.url);

    const upstream = net.createConnection({ host: '127.0.0.1', port }, () => {
        let rawHeaders = `${req.method} ${req.url} HTTP/${req.httpVersion}\r\n`;
        for (let i = 0; i < req.rawHeaders.length; i += 2) {
            rawHeaders += `${req.rawHeaders[i]}: ${req.rawHeaders[i + 1]}\r\n`;
        }
        rawHeaders += '\r\n';
        upstream.write(rawHeaders);
        if (head && head.length) upstream.write(head);
    });

    upstream.pipe(socket, { end: true });
    socket.pipe(upstream, { end: true });
    upstream.on('error', (err) => { console.error(`[Proxy] WS error → port ${port}: ${err.message}`); socket.destroy(); });
    socket.on('error', () => upstream.destroy());
}

async function start(desktopPort, swrlPort) {

    if (desktopPort) DESKTOP_PORT = desktopPort;
    if (swrlPort)    SWRL_PORT    = swrlPort;
    await findFreeProxyPort();

    return new Promise((resolve, reject) => {
        proxyServer = http.createServer(proxyHttp);
        proxyServer.on('upgrade', proxyWs);

        proxyServer.listen(PROXY_PORT, '127.0.0.1', () => {
            console.log(`[Proxy] Ready on http://127.0.0.1:${PROXY_PORT}`);
            console.log(`[Proxy]   /api/swrl/**    → :${SWRL_PORT}`);
            console.log(`[Proxy]   everything else → :${DESKTOP_PORT}`);
            resolve();
        });

        proxyServer.on('error', (err) => {
            console.error('[Proxy] Failed to start:', err.message);
            reject(err);
        });
    });
}

function stop() {
    return new Promise((resolve) => {
        if (!proxyServer) return resolve();
        proxyServer.close(() => resolve());
        proxyServer = null;
    });
}

module.exports = { start, stop, PROXY_PORT, DESKTOP_PORT, SWRL_PORT };
