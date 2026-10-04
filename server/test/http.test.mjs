import test from 'node:test';
import assert from 'node:assert/strict';
import { connect } from 'node:net';
import { rm } from 'node:fs/promises';
import { join } from 'node:path';
import { startServer, normalizePublicBaseUrl } from '../src/index.mjs';
import { SessionStore } from '../src/store.mjs';
import { harness, httpSession, inventory, commandPayload, result } from './helpers.mjs';

function failure(response, status, code) {
  assert.equal(response.status, status, response.text);
  assert.deepEqual(Object.keys(response.body), ['error']);
  assert.deepEqual(Object.keys(response.body.error).sort(), ['code', 'message']);
  assert.equal(response.body.error.code, code);
  assert.equal(typeof response.body.error.message, 'string');
  assert.ok(response.body.error.message.length > 0);
}

function security(response) {
  assert.equal(response.headers['cache-control'], 'no-store');
  assert.equal(response.headers['referrer-policy'], 'no-referrer');
  assert.equal(response.headers['x-content-type-options'], 'nosniff');
  assert.equal(response.headers['x-frame-options'], 'DENY');
  const csp = response.headers['content-security-policy'];
  for (const directive of ["default-src 'self'", "script-src 'self'", "object-src 'none'",
    "base-uri 'none'", "form-action 'none'", "frame-ancestors 'none'", "connect-src 'self'"]) {
    assert.ok(csp.includes(directive), csp);
  }
  assert.ok(!csp.includes('unsafe-inline'));
  assert.ok(!csp.includes('unsafe-eval'));
  assert.ok(!csp.includes('https:'));
  assert.ok(!csp.includes('*'));
  assert.equal(response.headers['access-control-allow-origin'], undefined);
}

test('startServer returns a listening native HTTP server and health is JSON with no-cache security headers', async (t) => {
  const h = await harness(t);
  assert.equal(h.server.listening, true);
  assert.equal(h.server.address().address, '127.0.0.1');
  assert.ok(h.server.address().port > 0);
  const response = await h.request('/api/health');
  assert.equal(response.status, 200);
  assert.deepEqual(response.body, { ok: true });
  security(response);
  assert.deepEqual(h.logs, []);
});

test('the exact create/claim/approve/uninstall/result contract works end-to-end without leaking inventory early', async (t) => {
  const h = await harness(t, { publicBaseUrl: 'https://assist.example/' });
  const created = await h.request('/api/sessions', {
    method: 'POST', json: { deviceName: '老人手机', inventory: inventory() },
  });
  assert.equal(created.status, 201);
  assert.deepEqual(Object.keys(created.body).sort(), ['sessionId', 'deviceToken', 'inviteToken', 'inviteUrl', 'expiresAt'].sort());
  const { sessionId, deviceToken, inviteToken } = created.body;
  assert.equal(created.body.inviteUrl, `https://assist.example/assist/#invite=${inviteToken}`);
  assert.equal('inventory' in created.body, false);
  const path = `/api/sessions/${sessionId}`;
  const claimed = await h.request('/api/claim', { method: 'POST', json: { inviteToken, childName: '女儿' } });
  assert.equal(claimed.status, 200);
  assert.equal(claimed.body.phase, 'claimed');
  assert.equal(claimed.body.sessionId, sessionId);
  assert.equal(claimed.body.expiresAt, created.body.expiresAt);
  const guestToken = claimed.body.guestToken;
  const waiting = await h.request(`${path}/guest`, { token: guestToken });
  assert.equal(waiting.body.phase, 'claimed');
  assert.equal(waiting.body.deviceOnline, false);
  assert.equal(waiting.body.lastDeviceSeen, null);
  assert.equal('inventory' in waiting.body, false);
  assert.equal('commands' in waiting.body, false);
  failure(await h.request(`${path}/commands`, { method: 'POST', token: guestToken, json: commandPayload() }), 403, 'APPROVAL_REQUIRED');
  const polled = await h.request(`${path}/device`, { token: deviceToken });
  assert.deepEqual(polled.body, { phase: 'claimed', childName: '女儿', commands: [], expiresAt: created.body.expiresAt });
  const approved = await h.request(`${path}/approve`, { method: 'POST', token: deviceToken, json: { approve: true } });
  assert.deepEqual(approved.body, { phase: 'approved' });
  const visible = await h.request(`${path}/guest`, { token: guestToken });
  assert.deepEqual(visible.body.inventory, inventory());
  assert.equal(visible.body.deviceOnline, true);
  assert.ok(visible.body.lastDeviceSeen);
  const queued = await h.request(`${path}/commands`, { method: 'POST', token: guestToken, json: commandPayload() });
  assert.equal(queued.status, 201);
  assert.equal(queued.body.status, 'pending');
  assert.equal(queued.body.action, 'uninstall');
  assert.deepEqual(queued.body.packages, ['org.example.notes']);
  assert.equal(queued.body.acknowledgeDataLoss, true);
  assert.ok(queued.body.id && queued.body.createdAt);
  assert.deepEqual((await h.request(`${path}/device`, { token: deviceToken })).body.commands, [queued.body]);
  const resultPath = `${path}/commands/${queued.body.id}/result`;
  const running = await h.request(resultPath, { method: 'POST', token: deviceToken, json: { status: 'running', results: [] } });
  assert.equal(running.body.status, 'running');
  const completed = await h.request(resultPath, {
    method: 'POST', token: deviceToken, json: { status: 'succeeded', results: [result('org.example.notes', 'succeeded', '已卸载')] },
  });
  assert.equal(completed.status, 200);
  assert.equal(completed.body.status, 'succeeded');
  assert.deepEqual((await h.request(`${path}/device`, { token: deviceToken })).body.commands, []);
  assert.deepEqual((await h.request(`${path}/guest`, { token: guestToken })).body.commands, [completed.body]);
  security(completed);
  assert.equal(completed.headers['strict-transport-security'], 'max-age=31536000');
  assert.deepEqual(h.logs, []);
});

test('concurrent invitation claims have exactly one winner and cannot replace the child identity', async (t) => {
  const h = await harness(t);
  const created = await h.request('/api/sessions', { method: 'POST', json: { deviceName: '手机', inventory: inventory() } });
  const [a, b] = await Promise.all(['甲', '乙'].map((childName) => h.request('/api/claim', {
    method: 'POST', json: { inviteToken: created.body.inviteToken, childName },
  })));
  assert.deepEqual([a.status, b.status].sort(), [200, 409]);
  const loser = a.status === 409 ? a : b;
  failure(loser, 409, 'INVITE_ALREADY_CLAIMED');
  const polled = await h.request(`/api/sessions/${created.body.sessionId}/device`, { token: created.body.deviceToken });
  assert.equal(polled.body.childName, a.status === 200 ? '甲' : '乙');
});

test('HTTP auth isolates roles, sessions, invites and all mutation endpoints', async (t) => {
  const h = await harness(t, { rateLimits: { authFailure: 100 } });
  const { session, guest, path } = await httpSession(h, true);
  const other = await httpSession(h, true);
  const cmd = await h.request(`${path}/commands`, { method: 'POST', token: guest.guestToken, json: commandPayload() });
  for (const token of [session.inviteToken, guest.guestToken, other.session.deviceToken, undefined]) {
    failure(await h.request(`${path}/device`, { token }), 401, 'INVALID_TOKEN');
    failure(await h.request(`${path}/approve`, { method: 'POST', token, json: { approve: true } }), 401, 'INVALID_TOKEN');
    failure(await h.request(`${path}/inventory`, { method: 'POST', token, json: { inventory: [] } }), 401, 'INVALID_TOKEN');
    failure(await h.request(path, { method: 'DELETE', token }), 401, 'INVALID_TOKEN');
    failure(await h.request(`${path}/commands/${cmd.body.id}/result`, {
      method: 'POST', token, json: { status: 'failed', results: [] },
    }), 401, 'INVALID_TOKEN');
  }
  for (const token of [session.deviceToken, session.inviteToken, other.guest.guestToken]) {
    failure(await h.request(`${path}/guest`, { token }), 401, 'INVALID_TOKEN');
    failure(await h.request(`${path}/commands`, { method: 'POST', token, json: commandPayload() }), 401, 'INVALID_TOKEN');
  }
  failure(await h.request(`${other.path}/commands/${cmd.body.id}/result`, {
    method: 'POST', token: other.session.deviceToken, json: { status: 'failed', results: [] },
  }), 404, 'COMMAND_NOT_FOUND');
  assert.equal((await h.request(`${path}/device`, { token: session.deviceToken })).body.commands.length, 1);
});

test('expiration, explicit revocation and fresh server stores invalidate every API capability', async (t) => {
  let now = 1000;
  const store = new SessionStore({ now: () => now, sessionTtlMs: 1000 });
  const h = await harness(t, { store, now: () => now });
  const a = await httpSession(h, true);
  now += 1000;
  failure(await h.request(`${a.path}/device`, { token: a.session.deviceToken }), 404, 'SESSION_NOT_FOUND');
  failure(await h.request(`${a.path}/guest`, { token: a.guest.guestToken }), 404, 'SESSION_NOT_FOUND');
  failure(await h.request('/api/claim', { method: 'POST', json: { inviteToken: a.session.inviteToken, childName: '甲' } }), 401, 'INVALID_INVITE');
  const b = await httpSession(h, true);
  const revoked = await h.request(b.path, { method: 'DELETE', token: b.session.deviceToken });
  assert.deepEqual(revoked.body, { phase: 'revoked' });
  failure(await h.request(`${b.path}/commands`, { method: 'POST', token: b.guest.guestToken, json: commandPayload() }), 404, 'SESSION_NOT_FOUND');
  failure(await h.request(`${b.path}/device`, { token: b.session.deviceToken }), 404, 'SESSION_NOT_FOUND');
  const c = await httpSession(h, true);
  const fresh = await harness(t);
  failure(await fresh.request(`${c.path}/guest`, { token: c.guest.guestToken }), 404, 'SESSION_NOT_FOUND');
});

test('deviceOnline and lastDeviceSeen are observable before approval; offline commands cannot be queued', async (t) => {
  let now = Date.UTC(2026, 9, 4);
  const store = new SessionStore({ now: () => now });
  const h = await harness(t, { store, now: () => now });
  const { path, session, guest } = await httpSession(h, true);
  const seen = new Date(now).toISOString();
  now += 30_000;
  const offline = await h.request(`${path}/guest`, { token: guest.guestToken });
  assert.equal(offline.body.deviceOnline, false);
  assert.equal(offline.body.lastDeviceSeen, seen);
  failure(await h.request(`${path}/commands`, { method: 'POST', token: guest.guestToken, json: commandPayload() }), 409, 'DEVICE_OFFLINE');
  assert.deepEqual(offline.body.commands, []);
  failure(await h.request(`${path}/device`, { token: guest.guestToken }), 401, 'INVALID_TOKEN');
  failure(await h.request(`${path}/inventory`, { method: 'POST', token: session.deviceToken, json: { inventory: null } }), 400, 'INVALID_INVENTORY');
  assert.equal((await h.request(`${path}/guest`, { token: guest.guestToken })).body.deviceOnline, false);
  await h.request(`${path}/inventory`, { method: 'POST', token: session.deviceToken, json: { inventory: inventory() } });
  const online = await h.request(`${path}/guest`, { token: guest.guestToken });
  assert.equal(online.body.deviceOnline, true);
  assert.equal(online.body.lastDeviceSeen, new Date(now).toISOString());
  assert.equal((await h.request(`${path}/commands`, { method: 'POST', token: guest.guestToken, json: commandPayload() })).status, 201);
  now += 30_000;
  await h.request(`${path}/device`, { token: session.deviceToken });
  assert.equal((await h.request(`${path}/guest`, { token: guest.guestToken })).body.deviceOnline, true);
});

test('mixed batches with core, unknown, critical or absent packages and missing consent are refused', async (t) => {
  const h = await harness(t);
  const { path, guest } = await httpSession(h, true);
  for (const name of ['org.example.core', 'org.example.unknown', 'org.example.locked', 'com.android.systemui', 'org.example.absent']) {
    failure(await h.request(`${path}/commands`, { method: 'POST', token: guest.guestToken,
      json: commandPayload(['org.example.notes', name]) }), 403, 'PACKAGE_NOT_ALLOWED');
  }
  failure(await h.request(`${path}/commands`, { method: 'POST', token: guest.guestToken,
    json: { action: 'uninstall', packages: ['org.example.notes'] } }), 400, 'DATA_LOSS_ACK_REQUIRED');
  failure(await h.request(`${path}/commands`, { method: 'POST', token: guest.guestToken,
    json: { ...commandPayload(), action: 'shell' } }), 400, 'UNSUPPORTED_ACTION');
  failure(await h.request(`${path}/commands`, { method: 'POST', token: guest.guestToken,
    json: commandPayload(['org.example.notes', 'org.example.notes']) }), 400, 'DUPLICATE_PACKAGE');
  failure(await h.request(`${path}/commands`, { method: 'POST', token: guest.guestToken,
    json: commandPayload(['org.example.notes;id']) }), 400, 'INVALID_PACKAGE');
  failure(await h.request(`${path}/commands`, { method: 'POST', token: guest.guestToken,
    json: commandPayload(Array(51).fill('org.example.notes')) }), 400, 'INVALID_PACKAGES');
  assert.deepEqual((await h.request(`${path}/guest`, { token: guest.guestToken })).body.commands, []);
});

test('false success, invalid per-package statuses and incomplete partial reports never mutate the command', async (t) => {
  const h = await harness(t);
  const { path, session, guest } = await httpSession(h, true);
  const cmd = await h.request(`${path}/commands`, { method: 'POST', token: guest.guestToken,
    json: commandPayload(['org.example.notes', 'org.example.game']) });
  const resultPath = `${path}/commands/${cmd.body.id}/result`;
  for (const [status, results, code] of [
    ['succeeded', [], 'INCONSISTENT_RESULTS'],
    ['succeeded', [result('org.example.notes'), result('org.example.game', 'failed')], 'INCONSISTENT_RESULTS'],
    ['partial', [result('org.example.notes')], 'INCONSISTENT_RESULTS'],
    ['succeeded', [result('org.example.notes'), result('org.example.notes')], 'INVALID_RESULTS'],
    ['running', [result('org.example.notes', 'running')], 'INVALID_RESULTS'],
    ['running', [result('org.example.notes', 'pending')], 'INVALID_RESULTS'],
  ]) {
    failure(await h.request(resultPath, { method: 'POST', token: session.deviceToken, json: { status, results } }), 400, code);
    assert.equal((await h.request(`${path}/device`, { token: session.deviceToken })).body.commands[0].status, 'pending');
  }
  const partial = await h.request(resultPath, { method: 'POST', token: session.deviceToken,
    json: { status: 'partial', results: [result('org.example.notes'), result('org.example.game', 'failed', '失败')] } });
  assert.equal(partial.status, 200);
  assert.equal(partial.body.status, 'partial');
  failure(await h.request(resultPath, { method: 'POST', token: session.deviceToken,
    json: { status: 'succeeded', results: [result('org.example.notes'), result('org.example.game')] } }), 409, 'COMMAND_TERMINAL');
});

test('all JSON mutation routes reject malformed JSON, non-object bodies and wrong content types consistently', async (t) => {
  const h = await harness(t, { rateLimits: { create: 100, claim: 100 } });
  const { path, session, guest } = await httpSession(h, true);
  const cmd = await h.request(`${path}/commands`, { method: 'POST', token: guest.guestToken, json: commandPayload() });
  const routes = [
    ['/api/sessions', undefined], ['/api/claim', undefined],
    [`${path}/approve`, session.deviceToken], [`${path}/inventory`, session.deviceToken],
    [`${path}/commands`, guest.guestToken], [`${path}/commands/${cmd.body.id}/result`, session.deviceToken],
  ];
  for (const [route, token] of routes) {
    for (const raw of ['{', '', '[invalid', '{"x":NaN}', '{"x":1}garbage']) {
      failure(await h.request(route, { method: 'POST', token, raw, headers: { 'Content-Type': 'application/json' } }), 400, 'INVALID_JSON');
    }
    for (const raw of ['null', '[]', '"string"', '5']) {
      failure(await h.request(route, { method: 'POST', token, raw, headers: { 'Content-Type': 'application/json' } }), 400, 'INVALID_BODY');
    }
    failure(await h.request(route, { method: 'POST', token, raw: '{}' }), 415, 'JSON_REQUIRED');
    failure(await h.request(route, { method: 'POST', token, raw: '{}', headers: { 'Content-Type': 'text/plain' } }), 415, 'JSON_REQUIRED');
  }
});

test('JSON limits cover content-length and chunked bodies, compression and malformed UTF-8', async (t) => {
  const h = await harness(t, { maxBodyBytes: 128, rateLimits: { create: 100 } });
  const jsonHeaders = { 'Content-Type': 'application/json' };
  failure(await h.request('/api/sessions', { method: 'POST', raw: ' '.repeat(129), headers: jsonHeaders }), 413, 'BODY_TOO_LARGE');
  failure(await h.request('/api/sessions', { method: 'POST', chunks: [' '.repeat(64), ' '.repeat(65)], headers: jsonHeaders }), 413, 'BODY_TOO_LARGE');
  failure(await h.request('/api/sessions', { method: 'POST', raw: '{}', headers: { ...jsonHeaders, 'Content-Encoding': 'gzip' } }), 415, 'UNSUPPORTED_ENCODING');
  const invalidUtf8 = Buffer.from([0x7b, 0x22, 0x78, 0x22, 0x3a, 0x22, 0xff, 0x22, 0x7d]);
  failure(await h.request('/api/sessions', { method: 'POST', raw: invalidUtf8, headers: jsonHeaders }), 400, 'INVALID_JSON');
  const valid = await h.request('/api/sessions', { method: 'POST', json: { deviceName: '手机', inventory: [] },
    headers: { 'Content-Type': 'application/json; charset=UTF-8' } });
  assert.equal(valid.status, 201);
  assert.deepEqual(h.logs, []);
});

test('static root, /assist/, external JS and HEAD work; missing static builds return readable JSON', async (t) => {
  const h = await harness(t);
  const root = await h.request('/');
  assert.equal(root.status, 200);
  assert.ok(root.text.includes('phone entry'));
  assert.equal(root.headers['content-type'], 'text/html; charset=utf-8');
  security(root);
  const assist = await h.request('/assist/?ignored=query');
  assert.equal(assist.status, 200);
  assert.ok(assist.text.includes('assist entry'));
  assert.ok(!assist.text.includes('phone entry'));
  security(assist);
  const redirect = await h.request('/assist');
  assert.equal(redirect.status, 308);
  assert.equal(redirect.headers.location, '/assist/');
  const js = await h.request('/app.js');
  assert.equal(js.status, 200);
  assert.equal(js.headers['content-type'], 'text/javascript; charset=utf-8');
  const head = await h.request('/app.js', { method: 'HEAD' });
  assert.equal(head.status, 200);
  assert.equal(head.text, '');
  assert.equal(head.headers['content-length'], js.headers['content-length']);
  assert.equal((await h.request('/safe.js')).status, 200);
  failure(await h.request('/not-found'), 404, 'FILE_NOT_FOUND');
  await rm(h.staticDir, { recursive: true });
  failure(await h.request('/assist/'), 404, 'FILE_NOT_FOUND');
  assert.equal((await h.request('/api/health')).status, 200);
});

test('raw and encoded traversal, backslashes, dotfiles, malformed paths and escaping symlinks cannot serve files', async (t) => {
  const h = await harness(t);
  for (const path of ['/../private.txt', '/%2e%2e/private.txt', '/assist/../index.html',
    '/assist/%2e%2e/index.html', '/%2e%2e%2fprivate.txt', '/assist/%5c..%5cprivate.txt',
    '/%00file', '/%', '/%ff', '/%3fescape', '/%23escape']) {
    const response = await h.request(path);
    failure(response, 400, 'INVALID_PATH');
    security(response);
    assert.ok(!response.text.includes('must never be served'));
  }
  for (const path of ['/.env', '/assist/.hidden', '/.git/config']) {
    failure(await h.request(path), 404, 'FILE_NOT_FOUND');
  }
  for (const path of ['/escape.txt', '/escape-dir/private.txt']) {
    failure(await h.request(path), 403, 'STATIC_PATH_FORBIDDEN');
  }
  failure(await h.request('/%252e%252e/private.txt'), 404, 'FILE_NOT_FOUND');
});

test('no wildcard CORS or cross-origin API access; unsupported routes and methods have unified errors', async (t) => {
  const h = await harness(t, { publicBaseUrl: 'https://assist.example' });
  failure(await h.request('/api/health', { headers: { Origin: 'https://attacker.example' } }), 403, 'ORIGIN_NOT_ALLOWED');
  failure(await h.request('/api/health', { headers: { Origin: 'null' } }), 403, 'ORIGIN_NOT_ALLOWED');
  const own = await h.request('/api/health', { headers: { Origin: 'https://assist.example' } });
  assert.equal(own.status, 200);
  security(own);
  for (const [path, method, allow] of [['/api/health', 'POST', 'GET'], ['/api/sessions', 'GET', 'POST'],
    ['/api/claim', 'OPTIONS', 'POST'], ['/app.js', 'POST', 'GET, HEAD']]) {
    const response = await h.request(path, { method });
    failure(response, 405, 'METHOD_NOT_ALLOWED');
    assert.equal(response.headers.allow, allow);
    security(response);
  }
  for (const path of ['/api', '/api/unknown', '/api/sessions/id/shell', '/api/sessions/id/device/cmd/result']) {
    failure(await h.request(path), 404, 'ROUTE_NOT_FOUND');
  }
});

test('creation and claim attempts are rate-limited by actual peer, not spoofed proxy headers, and recover after their window', async (t) => {
  let now = 0;
  const h = await harness(t, { now: () => now, rateLimits: { create: 1, claim: 1, windowMs: 1000 } });
  const created = await h.request('/api/sessions', { method: 'POST', json: { deviceName: '手机', inventory: [] } });
  assert.equal(created.status, 201);
  const tooMany = await h.request('/api/sessions', { method: 'POST', json: { deviceName: '手机', inventory: [] },
    headers: { 'X-Forwarded-For': '8.8.8.8' } });
  failure(tooMany, 429, 'RATE_LIMITED');
  assert.equal(tooMany.headers['retry-after'], '1');
  failure(await h.request('/api/claim', { method: 'POST', json: { inviteToken: 'invalid', childName: '子女' } }), 401, 'INVALID_INVITE');
  failure(await h.request('/api/claim', { method: 'POST', json: { inviteToken: created.body.inviteToken, childName: '子女' } }), 429, 'RATE_LIMITED');
  assert.equal((await h.request('/api/health')).status, 200);
  now = 1000;
  assert.equal((await h.request('/api/claim', { method: 'POST', json: { inviteToken: created.body.inviteToken, childName: '子女' } })).status, 200);
  assert.equal((await h.request('/api/sessions', { method: 'POST', json: { deviceName: '手机', inventory: [] } })).status, 201);
});

test('authentication failures are rate-limited without charging successful polling; expiry has bounded cleanup', async (t) => {
  let now = 0;
  const h = await harness(t, { now: () => now, rateLimits: { authFailure: 2, windowMs: 1000 } });
  const { path, session } = await httpSession(h);
  for (let i = 0; i < 4; i += 1) assert.equal((await h.request(`${path}/device`, { token: session.deviceToken })).status, 200);
  for (let i = 0; i < 2; i += 1) failure(await h.request(`${path}/device`, { token: 'x'.repeat(43) }), 401, 'INVALID_TOKEN');
  failure(await h.request(`${path}/device`, { token: session.deviceToken }), 429, 'RATE_LIMITED');
  now = 1000;
  assert.equal((await h.request(`${path}/device`, { token: session.deviceToken })).status, 200);
  const capped = await harness(t, { rateLimits: { maxBuckets: 1 } });
  const created = await capped.request('/api/sessions', { method: 'POST', json: { deviceName: '手机', inventory: [] } });
  failure(await capped.request('/api/claim', { method: 'POST', json: { inviteToken: created.body.inviteToken, childName: '子女' } }), 503, 'RATE_LIMIT_CAPACITY');
});

test('session caps do not grow memory forever and interval cleanup removes expired idle sessions', async (t) => {
  let now = 0;
  let cleanups = 0;
  class CountingStore extends SessionStore {
    cleanup() { cleanups += 1; super.cleanup(); }
  }
  const store = new CountingStore({ maxSessions: 1, sessionTtlMs: 100, now: () => now });
  const h = await harness(t, { store, cleanupIntervalMs: 5 });
  assert.equal((await h.request('/api/sessions', { method: 'POST', json: { deviceName: '手机', inventory: [] } })).status, 201);
  failure(await h.request('/api/sessions', { method: 'POST', json: { deviceName: '手机', inventory: [] } }), 503, 'SESSION_LIMIT');
  const before = cleanups;
  now = 100;
  await new Promise((resolveWait) => setTimeout(resolveWait, 25));
  assert.ok(cleanups > before, 'background expiry cleanup must actually run');
  assert.equal(store.size, 0);
  assert.equal((await h.request('/api/sessions', { method: 'POST', json: { deviceName: '手机', inventory: [] } })).status, 201);
});

test('public bases require HTTPS or loopback HTTP and public insecure bindings require explicit opt-in with a warning', async (t) => {
  for (const base of ['http://public.example', 'http://localhost.attacker.example', 'http://192.168.1.2',
    'ftp://localhost', 'https://user:password@example.com', 'https://example.com/path',
    'https://example.com?x=1', 'https://example.com/#token', 'not a URL', ' https://example.com']) {
    assert.throws(() => normalizePublicBaseUrl(base), (err) => err.code === 'INVALID_PUBLIC_BASE_URL', base);
  }
  for (const base of ['http://localhost:8787', 'http://127.0.0.1:8787', 'http://[::1]:8787', 'https://public.example']) {
    assert.equal(normalizePublicBaseUrl(`${base}/`), base);
  }
  await assert.rejects(startServer({ host: '0.0.0.0', port: 0 }), (err) => err.code === 'INSECURE_BIND');
  const development = await harness(t, { host: '0.0.0.0', allowInsecureDev: true });
  assert.ok(development.logs.some(([level, message]) => level === 'warn' && message.includes('WARNING')));
  const secureProxy = await harness(t, { host: '0.0.0.0', publicBaseUrl: 'https://assist.example' });
  assert.deepEqual(secureProxy.logs, []);
  for (const port of [-1, 65536, 1.5]) await assert.rejects(startServer({ port }), TypeError);
});

test('unexpected errors are visible but their raw messages, tokens and inventory never enter logs or responses', async (t) => {
  const secret = 'TOKEN-DO-NOT-LOG';
  const privateInventory = 'PRIVATE-INVENTORY-DO-NOT-LOG';
  class BrokenStore extends SessionStore {
    createSession() {
      throw new Error(`${secret} ${privateInventory}`);
    }
  }
  const h = await harness(t, { store: new BrokenStore() });
  const response = await h.request('/api/sessions', { method: 'POST', json: { deviceName: secret, inventory: [] } });
  failure(response, 500, 'INTERNAL_ERROR');
  assert.ok(h.logs.some(([level]) => level === 'error'));
  assert.ok(!JSON.stringify(h.logs).includes(secret));
  assert.ok(!JSON.stringify(h.logs).includes(privateInventory));
  assert.ok(!response.text.includes(secret));
  const inaccessible = await harness(t, { staticDir: join(h.fixture, 'missing-static-build') });
  failure(await inaccessible.request('/assist/'), 404, 'FILE_NOT_FOUND');
});

test('malformed raw HTTP is rejected with the standard error envelope and safe headers', async (t) => {
  const h = await harness(t);
  const address = h.server.address();
  const response = await new Promise((resolveResponse, reject) => {
    const socket = connect(address.port, address.address);
    let text = '';
    socket.setTimeout(2000, () => socket.destroy(new Error('raw HTTP test timeout')));
    socket.on('connect', () => socket.write('GET /api/health HTTP/1.1\r\nHost: localhost\r\nBad Header: x\r\n\r\n'));
    socket.on('data', (chunk) => { text += chunk; });
    socket.on('error', reject);
    socket.on('end', () => resolveResponse(text));
  });
  assert.match(response, /^HTTP\/1\.1 400 Bad Request/);
  assert.match(response, /Cache-Control: no-store/);
  assert.match(response, /Referrer-Policy: no-referrer/);
  assert.equal(JSON.parse(response.split('\r\n\r\n')[1]).error.code, 'INVALID_HTTP');
});
