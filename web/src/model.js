export const categories = { core: '核心组件', optional: '可清理预装', user: '自行安装', unknown: '需要核对' };
export function escapeHtml(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
export function removable(item) {
 return Boolean(item && item.removable === true && item.whitelisted !== true && ['optional','user'].includes(item.category));
}
export function plan(inventory, selected) {
 if (!Array.isArray(inventory) || !Array.isArray(selected) || !selected.length) throw new Error('请先选择要清理的软件。');
 const names = new Set(selected);
 if (names.size !== selected.length) throw new Error('清理名单有重复项目。');
 return selected.map(name => { const app = inventory.find(x => x.packageName === name); if (!removable(app)) throw new Error('核心组件或尚未确认的软件不能清理：' + name); return app; });
}
export function validBaseUrl(value) {
 const url = new URL(value);
 const local = ['localhost','127.0.0.1','[::1]'].includes(url.hostname);
 if (url.username || url.password || url.search || url.hash || (url.protocol !== 'https:' && !(local && url.protocol === 'http:'))) throw new Error('服务地址需使用HTTPS；仅本机调试允许HTTP。');
 if (url.pathname !== '/') throw new Error('请填写服务根地址，不要带页面路径。');
 return url.origin;
}
export function isLocalService(value) {
 try { return ['localhost','127.0.0.1','[::1]'].includes(new URL(value).hostname); } catch (_) { return false; }
}
export function taskLabel(status) { return ({pending:'等待手机确认',running:'正在清理','needs-confirmation':'需要手机点确认',succeeded:'已逐项核验',partial:'部分完成',failed:'未完成',rejected:'手机已拒绝'})[status] || '等待更新'; }
export function summarize(items) { return {removable:items.filter(removable).length,core:items.filter(x=>x.category==='core').length,unknown:items.filter(x=>x.category==='unknown').length}; }

// Current uninstall state is supplied by Native. Historical success is never a restore candidate.
export function removedAppItems(items) {
 const seen = new Set();
 return (Array.isArray(items) ? items : []).filter(item => {
  if (!item || typeof item.packageName !== 'string' || !item.packageName.trim() || seen.has(item.packageName)) return false;
  seen.add(item.packageName);
  return true;
 }).map(item => ({
  packageName:item.packageName, label:item.label || item.packageName, versionName:item.versionName || '',
  removedAt:item.removedAt, backupAvailable:item.backupAvailable === true, restorable:item.restorable === true,
  reason:item.reason || ''
 }));
}
export function restoreCandidates(items) { return removedAppItems(items).filter(item => item.restorable); }
const count = value => Number.isFinite(value) ? Math.max(0, Math.floor(value)) : 0;
export function restoreProgressView(progress = {}) {
 const total = count(progress.total), completed = Math.min(total, count(progress.completed));
 return { running:progress.running === true, total, completed, failed:Math.min(total, count(progress.failed)), currentLabel:progress.currentLabel || progress.currentPackage || '', message:progress.message || '' };
}
export function historyEntryView(item) {
 const action = item.action === 'uninstall' ? 'uninstall' : item.action === 'restore' ? 'restore' : 'legacy';
 const succeeded = item.status === 'succeeded';
 return {
  action, label:item.label || item.packageName || '软件处理',
  operation:action === 'uninstall' ? '清理' : action === 'restore' ? '恢复' : '历史操作',
  outcome:succeeded ? (action === 'restore' ? '已恢复' : action === 'uninstall' ? '清理完成' : '操作完成') : item.status === 'running' ? '处理中' : item.status === 'needs-confirmation' ? '等待系统确认' : item.status === 'rejected' ? '已取消' : item.status === 'failed' ? '未完成' : '等待更新',
  succeeded, message:item.message || '', time:item.time
 };
}
export function remoteSessionView(session, now = Date.now()) {
 if (!session) return {phase:'none', title:'尚未邀请家人', canShare:false, canApprove:false, approved:false, ended:false};
 let phase = session.phase;
 const expiry = new Date(session.expiresAt).getTime();
 if (phase !== 'revoked' && (phase === 'expired' || Number.isFinite(expiry) && expiry <= now)) phase = 'expired';
 const titles = {created:'等待家人领取',claimed:'请确认这位家人',approved:'家人协助中',expired:'邀请已过期',revoked:'协助已断开'};
 return {phase, title:titles[phase] || '协助状态待确认', canShare:phase === 'created' && Boolean(session.inviteUrl), canApprove:phase === 'claimed', approved:phase === 'approved', ended:phase === 'expired' || phase === 'revoked'};
}
export function errorPresentation(value) {
 const details = String(value || '操作未完成。');
 let summary = '这次没有完成，请稍后重试。';
 if (/过期|到期|失效|expired/i.test(details)) summary = '邀请已失效，请重新生成邀请。';
 else if (/撤销|revoked/i.test(details)) summary = '协助已断开，请重新邀请家人。';
 else if (/离线|未响应|offline/i.test(details)) summary = '手机暂未连接，请保持安心手机打开后重试。';
 else if (/超时|timeout|timed out/i.test(details)) summary = '手机响应较慢，请稍后重试。';
 else if (/fetch|network|connect|连接|网络|服务/i.test(details)) summary = '暂时连接不上，请检查网络或请家人帮助。';
 else if (/授权|权限|permission|shizuku|adb/i.test(details)) summary = '需要手机授权，请请家人检查设置。';
 else if (details.length <= 90 && !/[{}]|Exception|Error:|\bat\s+\w+\(/.test(details)) summary = details;
 return {summary, details};
}

export function recoverableApps(items) {
 if (!Array.isArray(items)) return [];
 const seen=new Set();
 return items.filter(item=>{if(!item||typeof item.packageName!=='string'||!item.restorable||!item.backupAvailable||seen.has(item.packageName))return false;seen.add(item.packageName);return true;});
}
