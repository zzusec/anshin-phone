import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SessionStore } from '../src/store.mjs';
import { inventory } from './helpers.mjs';

// 跨端契约：Android 端 RemoteDto 只上传这 6 个字段；时间统一 ISO-8601；phase 统一 created/claimed/approved；
// 每批任务上限 50。此测试防止任何一端单独漂移。

test('手机端上传的确切 DTO 形状（无 reason 等本地字段）被服务端接受', () => {
  const store = new SessionStore();
  const phoneInventory = [{
    packageName: 'com.huawei.appmarket',
    label: '应用市场',
    category: 'optional',
    removable: true,
    system: true,
    versionName: '10.4.0.300',
  }];
  const session = store.createSession({ deviceName: 'HUAWEI 测试机', inventory: phoneInventory });
  assert.equal(typeof session.sessionId, 'string');
  assert.ok(session.expiresAt, 'expiresAt 必须存在');
});

test('带 reason 等本地解释字段的清单被服务端拒绝（Android 端必须先经 RemoteDto 映射）', () => {
  const store = new SessionStore();
  assert.throws(
    () => store.createSession({
      deviceName: '老人手机',
      inventory: [{ ...inventory()[0], reason: '保护基本功能' }],
    }),
    (error) => error.code === 'INVALID_BODY',
  );
});

test('expiresAt 是可被 Date/Java Instant 解析的 UTC ISO-8601 字符串', () => {
  const store = new SessionStore();
  const session = store.createSession({ deviceName: '老人手机', inventory: inventory() });
  assert.match(session.expiresAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/u);
  const parsed = new Date(session.expiresAt);
  assert.equal(parsed.toISOString(), session.expiresAt);
  // 与 Java 端 RemoteDto.parseExpiresAt 相同的解析路径（ISO instant）。
  assert.ok(Number.isFinite(parsed.getTime()) && parsed.getTime() > Date.now() - 1000);
});

test('会话 phase 依次为 claimed → approved，手机端固定按 created 显示新邀请', () => {
  const store = new SessionStore();
  const session = store.createSession({ deviceName: '老人手机', inventory: inventory() });
  const guest = store.claim({ inviteToken: session.inviteToken, childName: '小明' });
  assert.equal(guest.phase, 'claimed');
  const approved = store.approve(session.sessionId, session.deviceToken, { approve: true });
  assert.equal(approved.phase, 'approved');
});

test('每批任务上限与手机端一致：50 包通过，51 包拒绝', () => {
  const packages = (count) => Array.from({ length: count }, (_, i) => `org.example.app${i}`);
  const inventoryFor = (count) => packages(count).map((packageName) => ({
    packageName, label: packageName, category: 'user', removable: true, system: false,
  }));

  const store = new SessionStore();
  const session = store.createSession({ deviceName: '老人手机', inventory: inventoryFor(51) });
  const guest = store.claim({ inviteToken: session.inviteToken, childName: '小明' });
  store.approve(session.sessionId, session.deviceToken, { approve: true });
  store.getDevice(session.sessionId, session.deviceToken);
  assert.throws(
    () => store.createCommand(session.sessionId, guest.guestToken, {
      action: 'uninstall', packages: packages(51), acknowledgeDataLoss: true,
    }),
    (error) => error.code === 'INVALID_PACKAGES',
  );

  const store2 = new SessionStore();
  const session2 = store2.createSession({ deviceName: '老人手机', inventory: inventoryFor(50) });
  const guest2 = store2.claim({ inviteToken: session2.inviteToken, childName: '小明' });
  store2.approve(session2.sessionId, session2.deviceToken, { approve: true });
  store2.getDevice(session2.sessionId, session2.deviceToken);
  const accepted = store2.createCommand(session2.sessionId, guest2.guestToken, {
    action: 'uninstall', packages: packages(50), acknowledgeDataLoss: true,
  });
  assert.equal(accepted.packages.length, 50);
});
