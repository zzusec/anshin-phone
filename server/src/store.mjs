import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';

export class ServiceError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = 'ServiceError';
    this.status = status;
    this.code = code;
  }
}

const CATEGORIES = new Set(['core', 'optional', 'user', 'unknown']);
const COMMAND_STATUSES = new Set([
  'running', 'needs-confirmation', 'succeeded', 'partial', 'failed', 'rejected',
]);
const PACKAGE_STATUSES = new Set([
  'needs-confirmation', 'succeeded', 'failed', 'rejected',
]);
const TERMINAL_COMMANDS = new Set(['succeeded', 'partial', 'failed', 'rejected']);
const TERMINAL_PACKAGES = new Set(['succeeded', 'failed', 'rejected']);
const DUMMY_HASH = createHash('sha256').update('not-a-session-token').digest();
const PACKAGE_PATTERN = /^(?:android|androidhwext|[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+)$/;

// Defense in depth: these names remain blocked even if a device mislabels them.
// This intentionally does not block every system app: known optional ones may be removed.
const CRITICAL_PACKAGES = new Set([
  'android', 'androidhwext', 'com.android.systemui', 'com.android.settings', 'com.android.shell',
  'com.android.phone', 'com.android.server.telecom', 'com.android.bluetooth',
  'com.android.nfc', 'com.android.keychain', 'com.android.certinstaller',
  'com.android.inputdevices', 'com.android.location.fused', 'com.android.proxyhandler',
  'com.android.emergency', 'com.android.managedprovisioning', 'com.android.provision',
  'com.android.externalstorage', 'com.android.webview', 'com.android.se',
  'com.google.android.webview', 'com.google.android.launcher', 'com.google.android.apps.nexuslauncher',
  'com.google.android.gms', 'com.google.android.gsf', 'com.google.android.gsf.login',
  'com.google.android.ext.services', 'com.google.android.ext.shared',
  'com.huawei.android.launcher', 'com.huawei.systemmanager', 'com.huawei.hwid',
  'com.huawei.hms', 'com.huawei.android.hwouc', 'com.huawei.android.internal.app',
]);
const CRITICAL_NAMESPACES = [
  'android', 'com.android.providers', 'com.android.permissioncontroller',
  'com.google.android.permissioncontroller', 'com.android.packageinstaller',
  'com.google.android.packageinstaller', 'com.android.networkstack',
  'com.google.android.networkstack', 'com.android.connectivity', 'com.android.wifi',
  'com.android.telephony', 'com.android.launcher', 'com.android.launcher2',
  'com.android.launcher3', 'com.android.inputmethod', 'com.google.android.inputmethod',
];

function fail(status, code, message) {
  throw new ServiceError(status, code, message);
}

function object(value, allowedKeys) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)
      || Object.getPrototypeOf(value) !== Object.prototype
      || Object.keys(value).some((key) => !allowedKeys.includes(key))) {
    fail(400, 'INVALID_BODY', '请求必须是 JSON 对象，且不能包含未支持的字段。');
  }
}

function text(value, name, max, min = 1) {
  if (typeof value !== 'string' || value.length < min || value.length > max
      || /[\u0000-\u001f\u007f]/u.test(value) || (min > 0 && !value.trim())) {
    fail(400, 'INVALID_FIELD', `${name} 格式不正确或超过长度限制。`);
  }
  return value;
}

function packageName(value) {
  if (typeof value !== 'string' || value.length > 255 || !PACKAGE_PATTERN.test(value)) {
    fail(400, 'INVALID_PACKAGE', '应用包名格式不正确。');
  }
  return value;
}

function hash(token) {
  return createHash('sha256').update(typeof token === 'string' ? token : '').digest();
}

function matches(token, expected) {
  // Hashes always have identical lengths, including malformed and missing tokens.
  const equal = timingSafeEqual(hash(token), expected ?? DUMMY_HASH);
  return typeof token === 'string' && /^[A-Za-z0-9_-]{43}$/.test(token) && equal;
}

function credential() {
  return randomBytes(32).toString('base64url');
}

function positiveInteger(value, name) {
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new TypeError(`${name} must be a positive safe integer`);
  }
}

export function isCriticalPackage(name) {
  const normalized = name.toLowerCase();
  return CRITICAL_PACKAGES.has(normalized)
    || CRITICAL_NAMESPACES.some((prefix) => normalized === prefix || normalized.startsWith(`${prefix}.`));
}

export class SessionStore {
  #sessions = new Map();
  #invites = new Map();
  #now;
  #ttl;
  #maxSessions;
  #maxInventory;
  #maxPackages;
  #maxCommands;

  constructor({
    now = Date.now,
    sessionTtlMs = 30 * 60 * 1000,
    maxSessions = 100,
    maxInventoryItems = 1000,
    maxPackagesPerCommand = 50,
    maxCommandsPerSession = 100,
  } = {}) {
    if (typeof now !== 'function') throw new TypeError('now must be a function');
    for (const [name, value] of Object.entries({
      sessionTtlMs, maxSessions, maxInventoryItems, maxPackagesPerCommand, maxCommandsPerSession,
    })) positiveInteger(value, name);
    this.#now = now;
    this.#ttl = sessionTtlMs;
    this.#maxSessions = maxSessions;
    this.#maxInventory = maxInventoryItems;
    this.#maxPackages = maxPackagesPerCommand;
    this.#maxCommands = maxCommandsPerSession;
  }

  get size() {
    this.cleanup();
    return this.#sessions.size;
  }

  cleanup() {
    const now = this.#now();
    for (const session of this.#sessions.values()) {
      if (session.expiresAtMs <= now) this.#remove(session);
    }
  }

  #remove(session) {
    this.#sessions.delete(session.id);
    this.#invites.delete(session.inviteHash.toString('hex'));
  }

  #session(id) {
    const session = this.#sessions.get(id);
    if (session && session.expiresAtMs <= this.#now()) this.#remove(session);
    if (!session || session.expiresAtMs <= this.#now()) {
      fail(404, 'SESSION_NOT_FOUND', '会话不存在、已到期或已被手机撤销，请重新邀请。');
    }
    return session;
  }

  #authenticate(id, token, role) {
    const session = this.#session(id);
    if (!matches(token, role === 'device' ? session.deviceHash : session.guestHash)) {
      fail(401, 'INVALID_TOKEN', '凭证无效，请使用此会话对应的手机或子女凭证。');
    }
    return session;
  }

  #approved(session) {
    if (session.phase !== 'approved') {
      fail(403, 'APPROVAL_REQUIRED', '请等待老人手机明确批准后再查看应用或提交清理任务。');
    }
  }

  #inventory(value) {
    if (!Array.isArray(value) || value.length > this.#maxInventory) {
      fail(400, 'INVALID_INVENTORY', `应用清单必须是数组，最多 ${this.#maxInventory} 项。`);
    }
    const seen = new Set();
    return value.map((item) => {
      object(item, ['packageName', 'label', 'category', 'removable', 'system', 'versionName']);
      const name = packageName(item.packageName);
      if (seen.has(name)) fail(400, 'DUPLICATE_PACKAGE', '应用清单中不能包含重复包名。');
      seen.add(name);
      text(item.label, 'label', 200);
      if (!CATEGORIES.has(item.category) || typeof item.removable !== 'boolean'
          || typeof item.system !== 'boolean') {
        fail(400, 'INVALID_INVENTORY', '每个应用必须包含有效分类、removable 和 system 布尔值。');
      }
      if (['android','androidhwext'].includes(name) && (item.category !== 'core' || item.removable || !item.system)) {
        fail(400, 'INVALID_INVENTORY', '系统框架应用必须保持不可清理的保护状态。');
      }
      if (item.versionName !== undefined) text(item.versionName, 'versionName', 100, 0);
      return { ...item };
    });
  }

  createSession(payload, publicBaseUrl = 'http://127.0.0.1:8787') {
    object(payload, ['deviceName', 'inventory']);
    const deviceName = text(payload.deviceName, 'deviceName', 100).trim();
    const inventory = this.#inventory(payload.inventory);
    this.cleanup();
    if (this.#sessions.size >= this.#maxSessions) {
      fail(503, 'SESSION_LIMIT', '服务会话数量已达上限，请稍后重试。');
    }
    const deviceToken = credential();
    const inviteToken = credential();
    const expiresAtMs = this.#now() + this.#ttl;
    const session = {
      id: randomUUID(), deviceName, inventory, phase: 'created', childName: null,
      deviceHash: hash(deviceToken), inviteHash: hash(inviteToken), guestHash: null,
      expiresAtMs, expiresAt: new Date(expiresAtMs).toISOString(), commands: [], lastDeviceSeenMs: null,
    };
    this.#sessions.set(session.id, session);
    this.#invites.set(session.inviteHash.toString('hex'), session.id);
    return {
      sessionId: session.id, deviceToken, inviteToken,
      inviteUrl: `${publicBaseUrl.replace(/\/$/u, '')}/assist/#invite=${inviteToken}`,
      expiresAt: session.expiresAt,
    };
  }

  claim(payload) {
    object(payload, ['inviteToken', 'childName']);
    const childName = text(payload.childName, 'childName', 100).trim();
    const inviteHash = hash(payload.inviteToken);
    const id = this.#invites.get(inviteHash.toString('hex'));
    const session = this.#sessions.get(id);
    const valid = matches(payload.inviteToken, session?.inviteHash);
    if (!valid || !session) fail(401, 'INVALID_INVITE', '邀请无效或已到期，请让老人手机重新邀请。');
    if (session.expiresAtMs <= this.#now()) {
      this.#remove(session);
      fail(401, 'INVALID_INVITE', '邀请无效或已到期，请让老人手机重新邀请。');
    }
    if (session.phase !== 'created') {
      fail(409, 'INVITE_ALREADY_CLAIMED', '此邀请已被领取，不能重复领取。');
    }
    const guestToken = credential();
    session.guestHash = hash(guestToken);
    session.childName = childName;
    session.phase = 'claimed';
    return { sessionId: session.id, guestToken, phase: 'claimed', expiresAt: session.expiresAt };
  }

  getDevice(id, token) {
    const session = this.#authenticate(id, token, 'device');
    session.lastDeviceSeenMs = this.#now();
    return {
      phase: session.phase, childName: session.childName,
      commands: structuredClone(session.commands.filter((command) => !TERMINAL_COMMANDS.has(command.status))),
      expiresAt: session.expiresAt,
    };
  }

  approve(id, token, payload) {
    const session = this.#authenticate(id, token, 'device');
    object(payload, ['approve']);
    if (payload.approve !== true) fail(400, 'INVALID_APPROVAL', '必须由手机明确提交 approve: true。');
    if (session.phase === 'created') fail(409, 'NOT_CLAIMED', '邀请尚未被子女领取，不能提前批准。');
    session.phase = 'approved';
    return { phase: 'approved' };
  }

  revoke(id, token) {
    const session = this.#authenticate(id, token, 'device');
    this.#remove(session);
    return { phase: 'revoked' };
  }

  updateInventory(id, token, payload) {
    const session = this.#authenticate(id, token, 'device');
    object(payload, ['inventory']);
    const inventory = this.#inventory(payload.inventory);
    session.inventory = inventory;
    session.lastDeviceSeenMs = this.#now();
    // Re-check undispatched requests; do not overwrite device-reported progress.
    for (const command of session.commands) {
      if (command.status !== 'pending') continue;
      if (command.packages.some((name) => !this.#removable(session, name))) {
        command.status = 'rejected';
        command.updatedAt = new Date(this.#now()).toISOString();
        command.results = command.packages.map((name) => ({
          packageName: name, status: 'rejected', message: '应用清单变化，原任务已失效；请重新确认。',
        }));
      }
    }
    return { phase: session.phase };
  }

  getGuest(id, token) {
    const session = this.#authenticate(id, token, 'guest');
    const response = {
      phase: session.phase, expiresAt: session.expiresAt,
      deviceOnline: this.#deviceOnline(session),
      lastDeviceSeen: session.lastDeviceSeenMs === null ? null : new Date(session.lastDeviceSeenMs).toISOString(),
    };
    if (session.phase === 'approved') {
      response.inventory = structuredClone(session.inventory);
      response.commands = structuredClone(session.commands);
    }
    return response;
  }

  #deviceOnline(session) {
    return session.lastDeviceSeenMs !== null && this.#now() - session.lastDeviceSeenMs < 30_000;
  }

  #removable(session, name) {
    const item = session.inventory.find((entry) => entry.packageName === name);
    return item && (item.category === 'optional' || item.category === 'user')
      && item.removable === true && !isCriticalPackage(name);
  }

  createCommand(id, token, payload) {
    const session = this.#authenticate(id, token, 'guest');
    this.#approved(session);
    if (!this.#deviceOnline(session)) {
      fail(409, 'DEVICE_OFFLINE', '手机助手已离线，请让老人保持手机助手打开并在前台协助页面等待。');
    }
    object(payload, ['action', 'packages', 'acknowledgeDataLoss']);
    if (payload.action !== 'uninstall') fail(400, 'UNSUPPORTED_ACTION', '只支持卸载清单中允许移除的应用。');
    if (payload.acknowledgeDataLoss !== true) {
      fail(400, 'DATA_LOSS_ACK_REQUIRED', '请明确确认卸载可能永久删除应用数据。');
    }
    if (!Array.isArray(payload.packages) || !payload.packages.length
        || payload.packages.length > this.#maxPackages) {
      fail(400, 'INVALID_PACKAGES', `每项任务必须包含 1 至 ${this.#maxPackages} 个应用包名。`);
    }
    const packages = payload.packages.map(packageName);
    if (new Set(packages).size !== packages.length) fail(400, 'DUPLICATE_PACKAGE', '任务中不能包含重复包名。');
    if (packages.some((name) => !this.#removable(session, name))) {
      fail(403, 'PACKAGE_NOT_ALLOWED', '任务含有核心、未知、关键系统或不允许移除的应用；整项任务已拒绝。');
    }
    if (session.commands.some((command) => !TERMINAL_COMMANDS.has(command.status)
        && command.packages.some((name) => packages.includes(name)))) {
      fail(409, 'PACKAGE_BUSY', '这些应用已有未完成任务，请等待原任务完成。');
    }
    if (session.commands.length >= this.#maxCommands) {
      fail(409, 'COMMAND_LIMIT', '本会话任务数量已达上限，请重新建立会话。');
    }
    const command = {
      id: randomUUID(), action: 'uninstall', packages, acknowledgeDataLoss: true,
      status: 'pending', createdAt: new Date(this.#now()).toISOString(),
    };
    session.commands.push(command);
    return structuredClone(command);
  }

  reportResult(id, token, commandId, payload) {
    const session = this.#authenticate(id, token, 'device');
    this.#approved(session);
    const command = session.commands.find((entry) => entry.id === commandId);
    if (!command) fail(404, 'COMMAND_NOT_FOUND', '此会话中不存在指定任务。');
    if (TERMINAL_COMMANDS.has(command.status)) fail(409, 'COMMAND_TERMINAL', '已完成或拒绝的任务不能改写结果。');
    object(payload, ['status', 'results']);
    if (!COMMAND_STATUSES.has(payload.status) || !Array.isArray(payload.results)
        || payload.results.length > command.packages.length) {
      fail(400, 'INVALID_RESULTS', '任务状态或逐应用结果格式不正确。');
    }
    const submitted = new Map();
    const merged = new Map((command.results ?? []).map((entry) => [entry.packageName, entry]));
    for (const item of payload.results) {
      object(item, ['packageName', 'status', 'message']);
      const name = packageName(item.packageName);
      if (!command.packages.includes(name) || submitted.has(name) || !PACKAGE_STATUSES.has(item.status)) {
        fail(400, 'INVALID_RESULTS', '结果必须使用任务内不重复的包名和有效的单应用状态。');
      }
      if (typeof item.message !== 'string' || item.message.length > 2000 || item.message.includes('\0')) {
        fail(400, 'INVALID_RESULTS', '每个应用结果必须包含不超过 2000 字符的 message 字符串。');
      }
      const previous = merged.get(name);
      if (previous && TERMINAL_PACKAGES.has(previous.status) && previous.status !== item.status) {
        fail(409, 'RESULT_REGRESSION', '不能把已经成功、失败或拒绝的应用结果改写为其他状态。');
      }
      const result = { ...item };
      submitted.set(name, result);
      merged.set(name, result);
    }
    const results = command.packages.filter((name) => merged.has(name)).map((name) => merged.get(name));
    const complete = submitted.size === command.packages.length;
    const allTerminal = results.every((item) => TERMINAL_PACKAGES.has(item.status));
    const successes = results.filter((item) => item.status === 'succeeded').length;
    if (payload.status === 'succeeded' && (!complete || successes !== command.packages.length)) {
      fail(400, 'INCONSISTENT_RESULTS', '成功状态要求提交完整逐应用结果，且每个应用均成功。');
    }
    if (payload.status === 'partial' && (!complete || !allTerminal
        || successes === 0 || successes === command.packages.length)) {
      fail(400, 'INCONSISTENT_RESULTS', '部分成功要求完整终态结果，且同时包含成功与失败或拒绝。');
    }
    if ((payload.status === 'failed' || payload.status === 'rejected')
        && (!allTerminal || successes > 0
          || (payload.status === 'rejected' && results.some((item) => item.status !== 'rejected')))) {
      fail(400, 'INCONSISTENT_RESULTS', '失败或拒绝状态不能隐藏成功结果或未结束的应用状态。');
    }
    command.status = payload.status;
    command.results = results;
    command.updatedAt = new Date(this.#now()).toISOString();
    return structuredClone(command);
  }
}
