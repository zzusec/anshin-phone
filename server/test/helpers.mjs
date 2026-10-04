import { request as httpRequest } from 'node:http';
import { mkdtemp, mkdir, writeFile, symlink, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { startServer } from '../src/index.mjs';
import { SessionStore } from '../src/store.mjs';

export function inventory() {
  return [
    { packageName: 'org.example.notes', label: '便签', category: 'user', removable: true, system: false, versionName: '1.0' },
    { packageName: 'org.example.game', label: '游戏', category: 'optional', removable: true, system: true },
    { packageName: 'org.example.core', label: '核心', category: 'core', removable: true, system: false },
    { packageName: 'org.example.unknown', label: '未知', category: 'unknown', removable: true, system: false },
    { packageName: 'org.example.locked', label: '不可移除', category: 'user', removable: false, system: false },
    { packageName: 'com.android.systemui', label: '错误标注的 System UI', category: 'user', removable: true, system: false },
  ];
}

export function approved(store = new SessionStore()) {
  const session = store.createSession({ deviceName: '老人手机', inventory: inventory() });
  const guest = store.claim({ inviteToken: session.inviteToken, childName: '小明' });
  store.approve(session.sessionId, session.deviceToken, { approve: true });
  store.getDevice(session.sessionId, session.deviceToken);
  return { store, session, guest };
}

export function commandPayload(packages = ['org.example.notes']) {
  return { action: 'uninstall', packages, acknowledgeDataLoss: true };
}

export function result(packageName, status = 'succeeded', message = '') {
  return { packageName, status, message };
}

export async function harness(t, options = {}) {
  // Even temporary fixtures stay within the sole authorized write scope.
  const fixture = await mkdtemp(join(import.meta.dirname, 'fixtures-'));
  const staticDir = join(fixture, 'dist');
  await mkdir(join(staticDir, 'assist'), { recursive: true });
  await writeFile(join(staticDir, 'index.html'), '<!doctype html><title>phone entry</title>');
  await writeFile(join(staticDir, 'assist', 'index.html'), '<!doctype html><title>assist entry</title><script src="/app.js"></script>');
  await writeFile(join(staticDir, 'app.js'), 'console.log("external script");');
  await writeFile(join(staticDir, '.env'), 'hidden test fixture');
  await writeFile(join(fixture, 'private.txt'), 'must never be served');
  await symlink(join(fixture, 'private.txt'), join(staticDir, 'escape.txt'));
  await symlink(fixture, join(staticDir, 'escape-dir'));
  await symlink(join(staticDir, 'app.js'), join(staticDir, 'safe.js'));
  const logs = [];
  const logger = Object.fromEntries(['info', 'warn', 'error'].map((level) => [level, (...args) => logs.push([level, ...args])]));
  const store = options.store ?? new SessionStore();
  let server;
  try {
    server = await startServer({ host: '127.0.0.1', port: 0, staticDir, logger, store, ...options });
  } catch (failure) {
    await rm(fixture, { recursive: true, force: true });
    throw failure;
  }
  t.after(async () => {
    await new Promise((resolveClose, reject) => {
      server.close((failure) => failure ? reject(failure) : resolveClose());
      server.closeAllConnections();
    });
    await rm(fixture, { recursive: true, force: true });
  });
  const address = server.address();
  return {
    server, store, logs, staticDir, fixture,
    async request(path, { method = 'GET', token, json, raw, headers = {}, chunks } = {}) {
      const requestHeaders = { ...headers };
      if (token !== undefined) requestHeaders.Authorization = `Bearer ${token}`;
      let body = raw;
      if (json !== undefined) {
        body = JSON.stringify(json);
        requestHeaders['Content-Type'] ??= 'application/json';
      }
      if (body !== undefined) requestHeaders['Content-Length'] = Buffer.byteLength(body);
      return new Promise((resolveResponse, reject) => {
        const req = httpRequest({ hostname: address.address, port: address.port, path, method, headers: requestHeaders }, (res) => {
          const parts = [];
          res.on('data', (chunk) => parts.push(chunk));
          res.on('error', reject);
          res.on('end', () => {
            const text = Buffer.concat(parts).toString('utf8');
            let parsed;
            if (text && res.headers['content-type']?.startsWith('application/json')) parsed = JSON.parse(text);
            resolveResponse({ status: res.statusCode, headers: res.headers, text, body: parsed });
          });
        });
        req.on('error', reject);
        if (chunks) for (const chunk of chunks) req.write(chunk);
        req.end(body);
      });
    },
  };
}

export async function httpSession(h, approve = false) {
  const created = await h.request('/api/sessions', {
    method: 'POST', json: { deviceName: '老人手机', inventory: inventory() },
  });
  const session = created.body;
  const claimed = await h.request('/api/claim', {
    method: 'POST', json: { inviteToken: session.inviteToken, childName: '小明' },
  });
  const guest = claimed.body;
  await h.request(`/api/sessions/${session.sessionId}/device`, { token: session.deviceToken });
  if (approve) {
    await h.request(`/api/sessions/${session.sessionId}/approve`, {
      method: 'POST', token: session.deviceToken, json: { approve: true },
    });
  }
  return { session, guest, path: `/api/sessions/${session.sessionId}` };
}
