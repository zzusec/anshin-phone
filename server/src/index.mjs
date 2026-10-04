import { createServer } from 'node:http';
import { isIP } from 'node:net';
import { open, realpath } from 'node:fs/promises';
import { resolve, relative, isAbsolute, extname, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { pipeline } from 'node:stream/promises';
import { SessionStore, ServiceError } from './store.mjs';

const DEFAULT_BASE_URL = 'http://127.0.0.1:8787';
const DEFAULT_STATIC_DIR = resolve(import.meta.dirname, '../../web/dist');
const SECURITY_HEADERS = {
  'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; worker-src 'none'",
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=()',
  'Cache-Control': 'no-store',
};
const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.webp': 'image/webp', '.ico': 'image/x-icon', '.woff': 'font/woff',
  '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8',
};

function error(status, code, message) {
  return new ServiceError(status, code, message);
}

function loopback(host) {
  const name = host.replace(/^\[|\]$/gu, '').toLowerCase();
  return name === 'localhost' || name === '::1' || (isIP(name) === 4 && name.startsWith('127.'));
}

export function normalizePublicBaseUrl(value) {
  let url;
  try {
    if (typeof value !== 'string' || value.trim() !== value) throw new TypeError();
    url = new URL(value);
  } catch {
    throw error(400, 'INVALID_PUBLIC_BASE_URL', 'PUBLIC_BASE_URL 必须是有效的 HTTPS 基址或本地调试 HTTP 基址。');
  }
  if (url.username || url.password || url.search || url.hash || url.pathname !== '/'
      || !(url.protocol === 'https:' || (url.protocol === 'http:' && loopback(url.hostname)))) {
    throw error(400, 'INVALID_PUBLIC_BASE_URL', 'PUBLIC_BASE_URL 只接受无路径、凭证、查询或 fragment 的 HTTPS 基址，或 localhost/loopback 调试 HTTP 基址。');
  }
  return url.origin;
}

function positiveInteger(value, name) {
  if (!Number.isSafeInteger(value) || value < 1) throw new TypeError(`${name} must be a positive safe integer`);
}

class RateLimiter {
  #buckets = new Map();
  #now;
  #limits;
  #window;
  #maxBuckets;

  constructor({ now, create = 10, claim = 20, authFailure = 20, windowMs = 60_000, maxBuckets = 10_000 }) {
    for (const [name, value] of Object.entries({ create, claim, authFailure, windowMs, maxBuckets })) {
      positiveInteger(value, `rateLimits.${name}`);
    }
    this.#now = now;
    this.#limits = { create, claim, authFailure };
    this.#window = windowMs;
    this.#maxBuckets = maxBuckets;
  }

  cleanup() {
    const now = this.#now();
    for (const [key, bucket] of this.#buckets) {
      if (bucket.until <= now) this.#buckets.delete(key);
    }
  }

  check(kind, ip, consume = true) {
    const key = `${kind}:${ip}`;
    const now = this.#now();
    let bucket = this.#buckets.get(key);
    if (bucket && bucket.until <= now) {
      this.#buckets.delete(key);
      bucket = undefined;
    }
    if (bucket && bucket.count >= this.#limits[kind]) {
      const failure = error(429, 'RATE_LIMITED', '请求过于频繁，请稍后重试。');
      failure.retryAfter = Math.max(1, Math.ceil((bucket.until - now) / 1000));
      throw failure;
    }
    if (!consume) return;
    if (!bucket) {
      if (this.#buckets.size >= this.#maxBuckets) this.cleanup();
      if (this.#buckets.size >= this.#maxBuckets) {
        throw error(503, 'RATE_LIMIT_CAPACITY', '服务请求容量已达上限，请稍后重试。');
      }
      bucket = { count: 0, until: now + this.#window };
      this.#buckets.set(key, bucket);
    }
    bucket.count += 1;
  }
}

function sendJson(res, status, body) {
  const encoded = Buffer.from(JSON.stringify(body));
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': encoded.length });
  res.end(encoded);
}

function bearer(req) {
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/u.exec(req.headers.authorization ?? '');
  if (!match) throw error(401, 'INVALID_TOKEN', '需要 Authorization: Bearer 会话凭证。');
  return match[1];
}

function decodePath(rawUrl) {
  const rawPath = (rawUrl ?? '/').split('?')[0];
  let path;
  try {
    path = decodeURIComponent(rawPath);
  } catch {
    throw error(400, 'INVALID_PATH', '请求路径编码无效。');
  }
  if (!path.startsWith('/') || /[\\\u0000-\u001f\u007f#?]/u.test(path)
      || path.split('/').some((part) => part === '.' || part === '..')) {
    throw error(400, 'INVALID_PATH', '请求路径包含不允许的字符或路径穿越。');
  }
  return path;
}

function readJson(req, maxBodyBytes) {
  if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/iu.test(req.headers['content-type'] ?? '')) {
    throw error(415, 'JSON_REQUIRED', '请使用 Content-Type: application/json 和 UTF-8 JSON 请求体。');
  }
  if (req.headers['content-encoding'] && req.headers['content-encoding'] !== 'identity') {
    throw error(415, 'UNSUPPORTED_ENCODING', '不支持压缩的 JSON 请求体。');
  }
  const length = Number(req.headers['content-length']);
  if (Number.isFinite(length) && length > maxBodyBytes) throw error(413, 'BODY_TOO_LARGE', 'JSON 请求体超过大小限制。');
  return new Promise((resolveBody, reject) => {
    let size = 0;
    const chunks = [];
    const finish = (failure, value) => {
      req.off('data', onData);
      req.off('end', onEnd);
      req.off('error', onError);
      req.off('aborted', onAborted);
      if (failure) reject(failure);
      else resolveBody(value);
    };
    const onData = (chunk) => {
      size += chunk.length;
      if (size > maxBodyBytes) {
        req.pause();
        finish(error(413, 'BODY_TOO_LARGE', 'JSON 请求体超过大小限制。'));
      } else chunks.push(chunk);
    };
    const onEnd = () => {
      try {
        // Fatal UTF-8 decoding avoids accepting replacement characters in identifiers.
        const decoded = new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks));
        finish(null, JSON.parse(decoded));
      } catch {
        finish(error(400, 'INVALID_JSON', '请求体不是有效的 UTF-8 JSON。'));
      }
    };
    const onError = () => finish(error(400, 'INVALID_BODY', '读取请求体失败。'));
    const onAborted = () => finish(error(400, 'INVALID_BODY', '请求体传输已中断。'));
    req.on('data', onData);
    req.once('end', onEnd);
    req.once('error', onError);
    req.once('aborted', onAborted);
  });
}

function method(req, res, expected) {
  if (req.method !== expected) {
    res.setHeader('Allow', expected);
    throw error(405, 'METHOD_NOT_ALLOWED', `此接口仅支持 ${expected}。`);
  }
}

function contained(root, target) {
  const path = relative(root, target);
  return path !== '..' && !path.startsWith(`..${sep}`) && !isAbsolute(path);
}

async function staticFile(req, res, path, staticDir) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.setHeader('Allow', 'GET, HEAD');
    throw error(405, 'METHOD_NOT_ALLOWED', '静态文件仅支持 GET 或 HEAD。');
  }
  if (path.split('/').some((segment) => segment.startsWith('.'))) {
    throw error(404, 'FILE_NOT_FOUND', '找不到此静态文件。');
  }
  if (path === '/assist') {
    res.writeHead(308, { Location: '/assist/' });
    res.end();
    return;
  }
  const name = path.endsWith('/') ? `${path}index.html` : path;
  let file;
  try {
    const root = await realpath(staticDir);
    const candidate = resolve(root, `.${name}`);
    if (!contained(root, candidate)) throw error(400, 'INVALID_PATH', '请求路径不在静态文件目录内。');
    const target = await realpath(candidate);
    if (!contained(root, target)) throw error(403, 'STATIC_PATH_FORBIDDEN', '不允许访问静态目录外的文件。');
    file = await open(target, 'r');
    const stat = await file.stat();
    if (!stat.isFile()) throw error(404, 'FILE_NOT_FOUND', '找不到此静态文件。');
    res.writeHead(200, {
      'Content-Type': MIME_TYPES[extname(target).toLowerCase()] ?? 'application/octet-stream',
      'Content-Length': stat.size,
    });
    if (req.method === 'HEAD') res.end();
    else await pipeline(file.createReadStream({ autoClose: false }), res);
  } catch (failure) {
    if (failure.code === 'ENOENT' || failure.code === 'ENOTDIR') {
      throw error(404, 'FILE_NOT_FOUND', '找不到此静态文件；请先构建网页。');
    }
    throw failure;
  } finally {
    if (file) await file.close();
  }
}

function safeErrorCode(failure) {
  const code = failure?.code;
  return typeof code === 'string' && /^[A-Z0-9_]{1,60}$/u.test(code) ? code : 'INTERNAL_ERROR';
}

/** Returns a listening native http.Server; server.address() includes the actual port. */
export async function startServer({
  host = '127.0.0.1',
  port = 8787,
  store = new SessionStore(),
  publicBaseUrl = DEFAULT_BASE_URL,
  staticDir = DEFAULT_STATIC_DIR,
  allowInsecureDev = false,
  maxBodyBytes = 256 * 1024,
  cleanupIntervalMs = 60_000,
  rateLimits = {},
  now = Date.now,
  logger = console,
} = {}) {
  if (typeof host !== 'string' || !host || host.trim() !== host) throw new TypeError('host must be a non-empty hostname');
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new TypeError('port must be between 0 and 65535');
  if (typeof now !== 'function') throw new TypeError('now must be a function');
  positiveInteger(maxBodyBytes, 'maxBodyBytes');
  positiveInteger(cleanupIntervalMs, 'cleanupIntervalMs');
  const base = normalizePublicBaseUrl(publicBaseUrl);
  if (!loopback(host) && !base.startsWith('https:')) {
    if (allowInsecureDev !== true) {
      throw error(400, 'INSECURE_BIND', '非 loopback 监听必须使用 HTTPS 公共基址；仅开发时可显式设置 ALLOW_INSECURE_DEV=1。');
    }
    logger.warn?.('[relay] WARNING: insecure development binding; credentials can be exposed. Never use this mode in production.');
  }
  const limiter = new RateLimiter({ ...rateLimits, now });
  const server = createServer({
    maxHeaderSize: 16 * 1024, requestTimeout: 15_000, headersTimeout: 10_000, keepAliveTimeout: 5000,
  }, async (req, res) => {
    for (const [name, value] of Object.entries(SECURITY_HEADERS)) res.setHeader(name, value);
    if (base.startsWith('https:')) res.setHeader('Strict-Transport-Security', 'max-age=31536000');
    const ip = req.socket.remoteAddress ?? 'unknown';
    let authenticatedRoute = false;
    try {
      const path = decodePath(req.url);
      if (path === '/api' || path.startsWith('/api/')) {
        if (req.headers.origin && req.headers.origin !== base) {
          throw error(403, 'ORIGIN_NOT_ALLOWED', '只允许从此服务自身的网页发起请求。');
        }
        if (path === '/api/health') {
          method(req, res, 'GET');
          sendJson(res, 200, { ok: true });
          return;
        }
        if (path === '/api/sessions') {
          method(req, res, 'POST');
          limiter.check('create', ip);
          sendJson(res, 201, store.createSession(await readJson(req, maxBodyBytes), base));
          return;
        }
        if (path === '/api/claim') {
          method(req, res, 'POST');
          limiter.check('claim', ip);
          sendJson(res, 200, store.claim(await readJson(req, maxBodyBytes)));
          return;
        }
        const route = /^\/api\/sessions\/([^/]+)(?:\/(device|guest|approve|inventory|commands)(?:\/([^/]+)\/(result))?)?$/u.exec(path);
        if (!route || (route[3] && route[2] !== 'commands')) {
          throw error(404, 'ROUTE_NOT_FOUND', '接口不存在。');
        }
        authenticatedRoute = true;
        limiter.check('authFailure', ip, false);
        const token = bearer(req);
        const [, id, endpoint, commandId] = route;
        let result;
        if (!endpoint) {
          method(req, res, 'DELETE');
          result = store.revoke(id, token);
        } else if (endpoint === 'device') {
          method(req, res, 'GET');
          result = store.getDevice(id, token);
        } else if (endpoint === 'guest') {
          method(req, res, 'GET');
          result = store.getGuest(id, token);
        } else if (endpoint === 'approve') {
          method(req, res, 'POST');
          result = store.approve(id, token, await readJson(req, maxBodyBytes));
        } else if (endpoint === 'inventory') {
          method(req, res, 'POST');
          result = store.updateInventory(id, token, await readJson(req, maxBodyBytes));
        } else if (endpoint === 'commands' && commandId) {
          method(req, res, 'POST');
          result = store.reportResult(id, token, commandId, await readJson(req, maxBodyBytes));
        } else {
          method(req, res, 'POST');
          result = store.createCommand(id, token, await readJson(req, maxBodyBytes));
        }
        sendJson(res, endpoint === 'commands' && !commandId ? 201 : 200, result);
      } else {
        await staticFile(req, res, path, staticDir);
      }
    } catch (failure) {
      if (authenticatedRoute && failure instanceof ServiceError
          && (failure.code === 'INVALID_TOKEN' || failure.code === 'SESSION_NOT_FOUND')) {
        try {
          limiter.check('authFailure', ip);
        } catch (rateFailure) {
          failure = rateFailure;
        }
      }
      if (!(failure instanceof ServiceError)) {
        // Never log request URLs, authorization, bodies, inventories, or raw errors.
        if (failure.code !== 'ERR_STREAM_PREMATURE_CLOSE' && failure.code !== 'ECONNRESET') {
          logger.error?.(`[relay] Unexpected server error (${safeErrorCode(failure)})`);
        }
        failure = error(500, 'INTERNAL_ERROR', '服务器处理请求失败，请稍后重试。');
      }
      if (res.destroyed) return;
      if (res.headersSent) {
        res.destroy();
        return;
      }
      if (!req.complete) res.setHeader('Connection', 'close');
      if (failure.retryAfter) res.setHeader('Retry-After', String(failure.retryAfter));
      sendJson(res, failure.status, { error: { code: failure.code, message: failure.message } });
    }
  });
  server.maxRequestsPerSocket = 100;
  server.on('clientError', (failure, socket) => {
    if (failure.code === 'ECONNRESET' || !socket.writable) {
      socket.destroy();
      return;
    }
    const body = JSON.stringify({ error: { code: 'INVALID_HTTP', message: 'HTTP 请求格式无效。' } });
    const headers = { ...SECURITY_HEADERS, 'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': Buffer.byteLength(body), Connection: 'close' };
    socket.end(`HTTP/1.1 400 Bad Request\r\n${Object.entries(headers).map(([name, value]) => `${name}: ${value}\r\n`).join('')}\r\n${body}`);
  });
  await new Promise((resolveListen, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      server.off('error', reject);
      resolveListen();
    });
  });
  const timer = setInterval(() => {
    try {
      store.cleanup();
      limiter.cleanup();
    } catch (failure) {
      logger.error?.(`[relay] Cleanup failed (${safeErrorCode(failure)})`);
    }
  }, cleanupIntervalMs);
  timer.unref();
  server.once('close', () => clearInterval(timer));
  return server;
}

async function main() {
  const port = process.env.PORT === undefined ? 8787 : Number(process.env.PORT);
  const server = await startServer({
    host: process.env.HOST ?? '127.0.0.1', port,
    publicBaseUrl: process.env.PUBLIC_BASE_URL ?? DEFAULT_BASE_URL,
    allowInsecureDev: process.env.ALLOW_INSECURE_DEV === '1',
  });
  const address = server.address();
  console.info(`[relay] Listening on ${address.address}:${address.port}; in-memory sessions only.`);
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, () => {
      server.close();
      const timer = setTimeout(() => server.closeAllConnections(), 5000);
      timer.unref();
    });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((failure) => {
    console.error(`[relay] Startup failed: ${failure instanceof ServiceError ? failure.message : safeErrorCode(failure)}`);
    process.exitCode = 1;
  });
}
