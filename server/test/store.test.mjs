import test from 'node:test';
import assert from 'node:assert/strict';
import { inspect } from 'node:util';
import { SessionStore, ServiceError, isCriticalPackage } from '../src/store.mjs';
import { approved, commandPayload, inventory, result } from './helpers.mjs';

function rejects(code, action, status) {
  assert.throws(action, (failure) => {
    assert.ok(failure instanceof ServiceError);
    assert.equal(failure.code, code);
    if (status !== undefined) assert.equal(failure.status, status);
    assert.ok(failure.message.length > 0);
    return true;
  });
}

function create(store = new SessionStore()) {
  return store.createSession({ deviceName: '老人手机', inventory: inventory() });
}

function task(context, packages) {
  return context.store.createCommand(context.session.sessionId, context.guest.guestToken, commandPayload(packages));
}

function report(context, command, status, results) {
  return context.store.reportResult(context.session.sessionId, context.session.deviceToken, command.id, { status, results });
}

test('credentials are independent 256-bit secrets, default expiry is 30 minutes, invite uses a fragment', () => {
  const now = Date.UTC(2026, 9, 4);
  const store = new SessionStore({ now: () => now });
  const session = store.createSession({ deviceName: '手机', inventory: [] }, 'https://assist.example');
  assert.equal(session.expiresAt, new Date(now + 30 * 60 * 1000).toISOString());
  assert.equal(session.inviteUrl, `https://assist.example/assist/#invite=${session.inviteToken}`);
  const guest = store.claim({ inviteToken: session.inviteToken, childName: '子女' });
  const tokens = [session.deviceToken, session.inviteToken, guest.guestToken];
  assert.equal(new Set(tokens).size, 3);
  for (const token of tokens) {
    assert.match(token, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(Buffer.from(token, 'base64url').length, 32);
    assert.ok(!inspect(store, { showHidden: true }).includes(token));
  }
  const device = store.getDevice(session.sessionId, session.deviceToken);
  assert.deepEqual(device, { phase: 'claimed', childName: '子女', commands: [], expiresAt: session.expiresAt });
  assert.ok(!JSON.stringify(device).includes('deviceToken'));
});

test('claim alone cannot read inventory or submit a command; approval cannot be given before claim', () => {
  const store = new SessionStore();
  const session = create(store);
  assert.equal(store.getDevice(session.sessionId, session.deviceToken).phase, 'created');
  rejects('NOT_CLAIMED', () => store.approve(session.sessionId, session.deviceToken, { approve: true }), 409);
  const guest = store.claim({ inviteToken: session.inviteToken, childName: '子女' });
  const pendingGuest = store.getGuest(session.sessionId, guest.guestToken);
  assert.equal(pendingGuest.phase, 'claimed');
  assert.equal(pendingGuest.expiresAt, session.expiresAt);
  assert.equal(pendingGuest.deviceOnline, true);
  assert.ok(pendingGuest.lastDeviceSeen);
  assert.equal('inventory' in pendingGuest, false);
  assert.equal('commands' in pendingGuest, false);
  rejects('APPROVAL_REQUIRED', () => store.createCommand(session.sessionId, guest.guestToken, commandPayload()), 403);
  rejects('INVALID_APPROVAL', () => store.approve(session.sessionId, session.deviceToken, { approve: false }));
  rejects('INVALID_APPROVAL', () => store.approve(session.sessionId, session.deviceToken, { approve: 'true' }));
  store.approve(session.sessionId, session.deviceToken, { approve: true });
  assert.deepEqual(store.getGuest(session.sessionId, guest.guestToken).inventory, inventory());
});

test('invites can be claimed exactly once, including after approval; invalid claims do not consume an invite', () => {
  const store = new SessionStore();
  const session = create(store);
  rejects('INVALID_FIELD', () => store.claim({ inviteToken: session.inviteToken, childName: '' }));
  store.claim({ inviteToken: session.inviteToken, childName: '女儿' });
  rejects('INVITE_ALREADY_CLAIMED', () => store.claim({ inviteToken: session.inviteToken, childName: '其他人' }), 409);
  store.approve(session.sessionId, session.deviceToken, { approve: true });
  rejects('INVITE_ALREADY_CLAIMED', () => store.claim({ inviteToken: session.inviteToken, childName: '其他人' }));
  rejects('INVALID_INVITE', () => store.claim({ inviteToken: 'x'.repeat(43), childName: '其他人' }), 401);
});

test('device, guest, invite and other-session tokens are isolated on all state-changing operations', () => {
  const context = approved();
  const { store, session, guest } = context;
  const second = create(store);
  const cmd = task(context);
  for (const token of [session.inviteToken, guest.guestToken, second.deviceToken, '', null, 'bad']) {
    rejects('INVALID_TOKEN', () => store.getDevice(session.sessionId, token), 401);
    rejects('INVALID_TOKEN', () => store.approve(session.sessionId, token, { approve: true }));
    rejects('INVALID_TOKEN', () => store.updateInventory(session.sessionId, token, { inventory: [] }));
    rejects('INVALID_TOKEN', () => store.revoke(session.sessionId, token));
    rejects('INVALID_TOKEN', () => store.reportResult(session.sessionId, token, cmd.id, { status: 'failed', results: [] }));
  }
  for (const token of [session.deviceToken, session.inviteToken, second.deviceToken, null]) {
    rejects('INVALID_TOKEN', () => store.getGuest(session.sessionId, token), 401);
    rejects('INVALID_TOKEN', () => store.createCommand(session.sessionId, token, commandPayload()));
  }
  rejects('INVALID_TOKEN', () => store.getGuest(second.sessionId, guest.guestToken));
  assert.equal(store.getDevice(session.sessionId, session.deviceToken).commands.length, 1);
});

test('expiry is enforced at the boundary, cleanup frees capacity and restart loses all sessions', () => {
  let now = 0;
  const store = new SessionStore({ now: () => now, sessionTtlMs: 100, maxSessions: 1 });
  const session = create(store);
  const guest = store.claim({ inviteToken: session.inviteToken, childName: '子女' });
  now = 99;
  assert.equal(store.getGuest(session.sessionId, guest.guestToken).phase, 'claimed');
  rejects('SESSION_LIMIT', () => create(store), 503);
  now = 100;
  rejects('SESSION_NOT_FOUND', () => store.getDevice(session.sessionId, session.deviceToken), 404);
  rejects('SESSION_NOT_FOUND', () => store.getGuest(session.sessionId, guest.guestToken));
  rejects('INVALID_INVITE', () => store.claim({ inviteToken: session.inviteToken, childName: '其他人' }));
  assert.equal(store.size, 0);
  const next = create(store);
  assert.equal(store.size, 1);
  rejects('SESSION_NOT_FOUND', () => new SessionStore().getDevice(next.sessionId, next.deviceToken));
  now += 100;
  store.cleanup();
  assert.equal(store.size, 0);
});

test('device revocation immediately invalidates all credentials and outstanding commands', () => {
  const context = approved();
  task(context);
  const { store, session, guest } = context;
  assert.deepEqual(store.revoke(session.sessionId, session.deviceToken), { phase: 'revoked' });
  assert.equal(store.size, 0);
  rejects('SESSION_NOT_FOUND', () => store.getDevice(session.sessionId, session.deviceToken));
  rejects('SESSION_NOT_FOUND', () => store.createCommand(session.sessionId, guest.guestToken, commandPayload()));
  rejects('INVALID_INVITE', () => store.claim({ inviteToken: session.inviteToken, childName: '子女' }));
});

test('core, unknown, non-removable, absent and mislabeled critical packages atomically reject the entire batch', () => {
  const context = approved();
  for (const name of ['org.example.core', 'org.example.unknown', 'org.example.locked', 'org.example.arbitrary', 'com.android.systemui']) {
    rejects('PACKAGE_NOT_ALLOWED', () => task(context, ['org.example.notes', name]), 403);
    assert.equal(context.store.getDevice(context.session.sessionId, context.session.deviceToken).commands.length, 0);
  }
  // An optional non-critical system app is allowed by the explicit product contract.
  assert.equal(task(context, ['org.example.game']).status, 'pending');
  for (const name of ['android', 'android.ext.services', 'com.android.providers.settings',
    'com.google.android.permissioncontroller', 'com.android.packageinstaller',
    'com.android.phone', 'com.huawei.android.launcher', 'COM.ANDROID.SYSTEMUI']) {
    assert.equal(isCriticalPackage(name), true, name);
  }
  assert.equal(isCriticalPackage('com.android.calendar'), false);
});

test('arbitrary actions, missing consent, duplicate/invalid/empty/excessive package lists are rejected', () => {
  const context = approved(new SessionStore({ maxPackagesPerCommand: 2 }));
  const { store, session, guest } = context;
  const submit = (payload) => store.createCommand(session.sessionId, guest.guestToken, payload);
  for (const action of ['shell', 'adb', 'install', '', null]) {
    rejects('UNSUPPORTED_ACTION', () => submit({ ...commandPayload(), action }));
  }
  for (const acknowledgeDataLoss of [false, undefined, 'true', 1]) {
    rejects('DATA_LOSS_ACK_REQUIRED', () => submit({ ...commandPayload(), acknowledgeDataLoss }));
  }
  for (const packages of [[], 'org.example.notes', ['org.example.notes', 'org.example.game', 'org.example.core']]) {
    rejects('INVALID_PACKAGES', () => submit(commandPayload(packages)));
  }
  rejects('DUPLICATE_PACKAGE', () => submit(commandPayload(['org.example.notes', 'org.example.notes'])));
  for (const name of ['org.example.notes; rm -rf /', '../org.example.notes', 'org/example/notes', 7, '', 'one', 'a'.repeat(256)]) {
    rejects('INVALID_PACKAGE', () => submit(commandPayload([name])));
  }
  rejects('INVALID_BODY', () => submit({ ...commandPayload(), shell: 'id' }));
  assert.equal(store.getGuest(session.sessionId, guest.guestToken).commands.length, 0);
});

test('active duplicate requests and per-session command growth are bounded', () => {
  const context = approved(new SessionStore({ maxCommandsPerSession: 1 }));
  const cmd = task(context);
  rejects('PACKAGE_BUSY', () => task(context));
  report(context, cmd, 'rejected', []);
  rejects('COMMAND_LIMIT', () => task(context, ['org.example.game']));
});

test('inventory validation rejects ambiguity and malformed fields without changing stored data', () => {
  const context = approved(new SessionStore({ maxInventoryItems: 10 }));
  const { store, session, guest } = context;
  const invalid = [
    null, {}, [inventory()[0], inventory()[0]],
    [{ ...inventory()[0], category: 'safe' }],
    [{ ...inventory()[0], removable: 'true' }],
    [{ ...inventory()[0], system: 0 }],
    [{ ...inventory()[0], label: '' }],
    [{ ...inventory()[0], versionName: 1 }],
    [{ ...inventory()[0], injected: 'field' }],
    Array(11).fill(inventory()[0]),
  ];
  for (const value of invalid) {
    assert.throws(() => store.updateInventory(session.sessionId, session.deviceToken, { inventory: value }), ServiceError);
    assert.deepEqual(store.getGuest(session.sessionId, guest.guestToken).inventory, inventory());
  }
  for (const payload of [null, [], 'body', 1, { deviceName: '手机', inventory: [], arbitrary: true }]) {
    rejects('INVALID_BODY', () => store.createSession(payload));
  }
});

test('returned and supplied objects cannot mutate private store state', () => {
  const store = new SessionStore();
  const original = inventory();
  const session = store.createSession({ deviceName: '手机', inventory: original });
  const guest = store.claim({ inviteToken: session.inviteToken, childName: '子女' });
  store.approve(session.sessionId, session.deviceToken, { approve: true });
  store.getDevice(session.sessionId, session.deviceToken);
  original[0].removable = false;
  original.push({ ...original[0], packageName: 'org.example.injection' });
  const view = store.getGuest(session.sessionId, guest.guestToken);
  view.inventory[0].category = 'core';
  const cmd = store.createCommand(session.sessionId, guest.guestToken, commandPayload());
  cmd.packages.push('com.android.systemui');
  assert.deepEqual(store.getDevice(session.sessionId, session.deviceToken).commands[0].packages, ['org.example.notes']);
  assert.equal(store.getGuest(session.sessionId, guest.guestToken).inventory.length, inventory().length);
});

test('inventory refresh rejects newly unsafe pending work, but does not erase device-reported progress', () => {
  const context = approved();
  const cmd = task(context);
  const { store, session, guest } = context;
  const changed = inventory().map((item) => ({ ...item, removable: false }));
  store.updateInventory(session.sessionId, session.deviceToken, { inventory: changed });
  assert.equal(store.getDevice(session.sessionId, session.deviceToken).commands.length, 0);
  const history = store.getGuest(session.sessionId, guest.guestToken).commands;
  assert.equal(history[0].status, 'rejected');
  assert.equal(history[0].results[0].status, 'rejected');
  rejects('COMMAND_TERMINAL', () => report(context, cmd, 'succeeded', [result('org.example.notes')]));
  rejects('PACKAGE_NOT_ALLOWED', () => task(context));
  store.updateInventory(session.sessionId, session.deviceToken, { inventory: inventory() });
  const running = task(context);
  report(context, running, 'running', []);
  store.updateInventory(session.sessionId, session.deviceToken, { inventory: [] });
  assert.equal(store.getDevice(session.sessionId, session.deviceToken).commands[0].status, 'running');
  assert.equal(report(context, running, 'succeeded', [result('org.example.notes')]).status, 'succeeded');
});

test('device polling returns only nonterminal work and preserves the entire uninstall request; guest keeps history', () => {
  const context = approved();
  const cmd = task(context, ['org.example.notes', 'org.example.game']);
  assert.equal(cmd.action, 'uninstall');
  assert.equal(cmd.acknowledgeDataLoss, true);
  assert.ok(cmd.id && cmd.createdAt);
  report(context, cmd, 'running', []);
  report(context, cmd, 'needs-confirmation', [result('org.example.notes', 'needs-confirmation')]);
  let polled = context.store.getDevice(context.session.sessionId, context.session.deviceToken).commands[0];
  assert.equal(polled.action, 'uninstall');
  assert.deepEqual(polled.packages, cmd.packages);
  assert.equal(polled.acknowledgeDataLoss, true);
  assert.equal(polled.status, 'needs-confirmation');
  report(context, cmd, 'partial', [result('org.example.notes'), result('org.example.game', 'rejected', '手机拒绝')]);
  assert.deepEqual(context.store.getDevice(context.session.sessionId, context.session.deviceToken).commands, []);
  polled = context.store.getGuest(context.session.sessionId, context.guest.guestToken).commands[0];
  assert.equal(polled.status, 'partial');
  assert.equal(polled.results.length, 2);
  rejects('COMMAND_TERMINAL', () => report(context, cmd, 'succeeded', [result('org.example.notes'), result('org.example.game')]));
});

test('succeeded and partial require complete, non-duplicated and semantically consistent package results', () => {
  const context = approved();
  const cmd = task(context, ['org.example.notes', 'org.example.game']);
  const cases = [
    ['succeeded', [], 'INCONSISTENT_RESULTS'],
    ['succeeded', [result('org.example.notes')], 'INCONSISTENT_RESULTS'],
    ['succeeded', [result('org.example.notes'), result('org.example.game', 'failed')], 'INCONSISTENT_RESULTS'],
    ['succeeded', [result('org.example.notes'), result('org.example.game', 'needs-confirmation')], 'INCONSISTENT_RESULTS'],
    ['partial', [result('org.example.notes')], 'INCONSISTENT_RESULTS'],
    ['partial', [result('org.example.notes'), result('org.example.game')], 'INCONSISTENT_RESULTS'],
    ['partial', [result('org.example.notes', 'failed'), result('org.example.game', 'failed')], 'INCONSISTENT_RESULTS'],
    ['partial', [result('org.example.notes'), result('org.example.game', 'needs-confirmation')], 'INCONSISTENT_RESULTS'],
    ['succeeded', [result('org.example.notes'), result('org.example.notes')], 'INVALID_RESULTS'],
    ['succeeded', [result('org.example.notes'), result('org.example.arbitrary')], 'INVALID_RESULTS'],
    ['succeeded', [result('org.example.notes'), { packageName: 'org.example.game', status: 'succeeded' }], 'INVALID_RESULTS'],
    ['success', [], 'INVALID_RESULTS'],
    ['running', [result('org.example.notes', 'partial')], 'INVALID_RESULTS'],
    ['running', [result('org.example.notes', 'running')], 'INVALID_RESULTS'],
    ['running', [result('org.example.notes', 'pending')], 'INVALID_RESULTS'],
  ];
  for (const [status, results, code] of cases) {
    rejects(code, () => report(context, cmd, status, results), 400);
    assert.equal(context.store.getDevice(context.session.sessionId, context.session.deviceToken).commands[0].status, 'pending');
  }
  report(context, cmd, 'succeeded', [result('org.example.notes'), result('org.example.game')]);
  assert.equal(context.store.getGuest(context.session.sessionId, context.guest.guestToken).commands[0].status, 'succeeded');
});

test('progress snapshots retain prior package results and cannot conceal or regress success', () => {
  const context = approved();
  const cmd = task(context, ['org.example.notes', 'org.example.game']);
  report(context, cmd, 'running', [result('org.example.notes')]);
  const next = report(context, cmd, 'needs-confirmation', [result('org.example.game', 'needs-confirmation')]);
  assert.equal(next.results.length, 2);
  rejects('RESULT_REGRESSION', () => report(context, cmd, 'running', [result('org.example.notes', 'needs-confirmation')]), 409);
  rejects('INCONSISTENT_RESULTS', () => report(context, cmd, 'failed', [result('org.example.game', 'failed')]));
  rejects('INCONSISTENT_RESULTS', () => report(context, cmd, 'rejected', []));
  rejects('INCONSISTENT_RESULTS', () => report(context, cmd, 'partial', [result('org.example.game', 'failed')]));
  const final = report(context, cmd, 'partial', [result('org.example.notes'), result('org.example.game', 'failed')]);
  assert.equal(final.status, 'partial');
});

test('failed and rejected terminal reports are accepted without inventing successes', () => {
  for (const [status, results] of [
    ['failed', []], ['rejected', []],
    ['failed', [result('org.example.notes', 'failed', '卸载失败')]],
    ['rejected', [result('org.example.notes', 'rejected', '老人取消')]],
  ]) {
    const context = approved();
    const cmd = task(context);
    assert.equal(report(context, cmd, status, results).status, status);
    assert.equal(context.store.getDevice(context.session.sessionId, context.session.deviceToken).commands.length, 0);
  }
  const context = approved();
  const cmd = task(context);
  for (const status of ['failed', 'rejected']) {
    rejects('INCONSISTENT_RESULTS', () => report(context, cmd, status, [result('org.example.notes')]));
  }
});

test('command IDs are scoped to their session and result payloads remain strictly structured', () => {
  const store = new SessionStore();
  const a = approved(store);
  const b = approved(store);
  const cmd = task(a);
  rejects('COMMAND_NOT_FOUND', () => report(b, cmd, 'succeeded', [result('org.example.notes')]), 404);
  rejects('INVALID_BODY', () => store.reportResult(a.session.sessionId, a.session.deviceToken, cmd.id, {
    status: 'running', results: [], shell: 'id',
  }));
  for (const results of [null, {}, [null], [result('org.example.notes', 'needs-confirmation', 'a'.repeat(2001))],
    [result('org.example.notes', 'needs-confirmation', 'bad\0message')],
    [{ ...result('org.example.notes'), arbitrary: true }]]) {
    assert.throws(() => report(a, cmd, 'running', results), ServiceError);
  }
});

test('only authenticated device polls or valid inventory updates refresh the 30-second online window', () => {
  let now = Date.UTC(2026, 9, 4);
  const store = new SessionStore({ now: () => now });
  const session = create(store);
  const guest = store.claim({ inviteToken: session.inviteToken, childName: '子女' });
  const view = () => store.getGuest(session.sessionId, guest.guestToken);
  assert.equal(view().deviceOnline, false);
  assert.equal(view().lastDeviceSeen, null);
  assert.equal('inventory' in view(), false);
  store.approve(session.sessionId, session.deviceToken, { approve: true });
  rejects('DEVICE_OFFLINE', () => store.createCommand(session.sessionId, guest.guestToken, commandPayload()), 409);
  rejects('INVALID_TOKEN', () => store.getDevice(session.sessionId, guest.guestToken));
  assert.equal(view().deviceOnline, false);
  store.getDevice(session.sessionId, session.deviceToken);
  assert.equal(view().deviceOnline, true);
  const firstSeen = new Date(now).toISOString();
  assert.equal(view().lastDeviceSeen, firstSeen);
  const context = { store, session, guest };
  const cmd = task(context);
  now += 29_999;
  assert.equal(view().deviceOnline, true);
  now += 1;
  assert.equal(view().deviceOnline, false);
  assert.equal(view().lastDeviceSeen, firstSeen);
  rejects('DEVICE_OFFLINE', () => task(context, ['org.example.game']), 409);
  assert.equal(view().commands.length, 1);
  // Approval and result reporting are not heartbeats.
  store.approve(session.sessionId, session.deviceToken, { approve: true });
  report(context, cmd, 'rejected', []);
  assert.equal(view().deviceOnline, false);
  assert.throws(() => store.updateInventory(session.sessionId, session.deviceToken, { inventory: null }), ServiceError);
  assert.equal(view().lastDeviceSeen, firstSeen);
  store.updateInventory(session.sessionId, session.deviceToken, { inventory: inventory() });
  assert.equal(view().lastDeviceSeen, new Date(now).toISOString());
  assert.equal(view().deviceOnline, true);
  assert.equal(task(context, ['org.example.game']).status, 'pending');
});
