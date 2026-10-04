let sequence = 0;
const pending = new Map();
window.addEventListener('native-result', event => {
 const value = event.detail;
 const request = pending.get(value.id);
 if (!request) return;
 clearTimeout(request.timer); pending.delete(value.id);
 if (value.ok) request.resolve(value.result); else request.reject(new Error(value.error || '手机未完成操作，请重试。'));
});
export function hasNative() { return Boolean(window.AnshinNative && typeof window.AnshinNative.call === 'function'); }
export async function native(action, data = {}) {
 if (!hasNative()) throw new Error('这是网页界面预览。请安装手机端后操作，网页不能直接清理手机。');
 return new Promise((resolve, reject) => {
  const id = 'r' + (++sequence);
  const timer = setTimeout(() => {pending.delete(id);reject(new Error('手机响应超时，请检查连接和权限。'));}, 60000);
  pending.set(id,{resolve,reject,timer});
  try {window.AnshinNative.call(JSON.stringify({id,action,data}));} catch (error) {clearTimeout(timer);pending.delete(id);reject(error);}
 });
}
export async function api(path, token, data, method) {
 const headers = {Accept:'application/json'};
 if (token) headers.Authorization = 'Bearer ' + token;
 if (data !== undefined) headers['Content-Type'] = 'application/json';
 const response = await fetch(path,{method:method || (data === undefined?'GET':'POST'),headers,body:data===undefined?undefined:JSON.stringify(data),credentials:'same-origin',cache:'no-store',referrerPolicy:'no-referrer'});
 let body;
 try {body=await response.json();} catch (_) {throw new Error('协助服务没有返回有效结果。请检查服务地址。');}
 if (!response.ok) throw new Error(body.error && body.error.message || '请求未完成，请重试。');
 return body;
}
