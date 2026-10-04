/** One computer-side setup after USB debugging consent. No root, public ADB, or personal-app deletion. */
import {execFileSync,spawn} from 'node:child_process';
import {mkdirSync,openSync,writeFileSync,existsSync,readFileSync} from 'node:fs';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const args=process.argv.slice(2),options={};
for(let i=0;i<args.length;i++){if(!['--serial','--apk','--server-url'].includes(args[i])||!args[i+1])throw new Error('用法：node scripts/adb-setup.mjs [--serial 序列号] [--apk 安装包] [--server-url HTTPS地址]');options[args[i].slice(2)]=args[++i];}
const quote=value=>"'"+value.replaceAll("'","'\\''")+"'";
function adb(...args){return execFileSync('adb',args,{encoding:'utf8',timeout:45000}).trim();}
const devices=adb('devices').split('\n').slice(1).map(line=>line.trim().split(/\s+/)).filter(parts=>parts[1]==='device').map(parts=>parts[0]);
const serial=options.serial||(devices.length===1?devices[0]:null);
if(!serial||!devices.includes(serial)||!/^[A-Za-z0-9_.:-]+$/.test(serial))throw new Error('请连接一台手机并允许USB调试。多台设备时由电脑端明确指定目标。');
const shell=command=>adb('-s',serial,'shell',command);
const sdk=Number(shell('getprop ro.build.version.sdk'));
if(sdk<28)throw new Error('目前支持Android9及以上，不对旧设备擅自降级。');
if(shell('am get-current-user')!=='0')throw new Error('目前只为机主用户0初始化，不修改其他用户。');
const url=new URL(options['server-url']||process.env.ANSHIN_REMOTE_URL||'http://127.0.0.1:8787');
const local=['localhost','127.0.0.1','[::1]'].includes(url.hostname);
if(url.username||url.password||url.search||url.hash||url.pathname!=='/'||(url.protocol!=='https:'&&!(local&&url.protocol==='http:')))throw new Error('协助地址必须是HTTPS根地址，本机USB联调例外。');
const serverUrl=url.origin;
const logs=resolve(root,'.handoff','adb-setup');mkdirSync(logs,{recursive:true,mode:0o700});
if(local){
 const port=Number(url.port||80);
 let reachable=false;try{const r=await fetch(serverUrl+'/api/health',{signal:AbortSignal.timeout(2000)});reachable=r.ok&&(await r.json()).ok===true;}catch{}
 if(!reachable){
  const log=openSync(resolve(logs,'server.log'),'a',0o600);
  const child=spawn(process.execPath,[resolve(root,'server/src/index.mjs')],{cwd:root,env:{...process.env,HOST:'127.0.0.1',PORT:String(port),PUBLIC_BASE_URL:serverUrl},detached:true,stdio:['ignore',log,log]});child.unref();writeFileSync(resolve(logs,'server.pid'),String(child.pid)+'\n');
  for(let i=0;i<15;i++){await new Promise(r=>setTimeout(r,300));try{const r=await fetch(serverUrl+'/api/health',{signal:AbortSignal.timeout(1000)});if(r.ok&&(await r.json()).ok===true){reachable=true;break;}}catch{}}
 }
 if(!reachable)throw new Error('电脑协助服务未就绪，未伪装为远程可用。');
 adb('-s',serial,'reverse',`tcp:${url.port||80}`,`tcp:${url.port||80}`);
 console.log('协助地址自动配置（USB本机联调，不是公网）：'+serverUrl);
}else{
 const r=await fetch(serverUrl+'/api/health',{signal:AbortSignal.timeout(10000)});
 if(!r.ok||(await r.json()).ok!==true)throw new Error('指定的HTTPS协助服务未通过健康检查。');
 console.log('协助地址自动配置：'+serverUrl);
}
if(options.apk){const apk=resolve(root,options.apk);if(!existsSync(apk))throw new Error('安装包不存在。');console.log(adb('-s',serial,'install','--user','0','-r',apk));}
shell('appops set com.anshin.phone ACTIVATE_VPN allow');
shell('appops set com.anshin.phone REQUEST_INSTALL_PACKAGES allow');
if(sdk>=33)shell('pm grant com.anshin.phone android.permission.POST_NOTIFICATIONS');
const pathLine=shell('pm path com.anshin.phone').split('\n').find(line=>line.startsWith('package:')&&line.endsWith('/base.apk'));
if(!pathLine)throw new Error('未找到已安装的安心手机base.apk。');
const apkPath=pathLine.slice(8);
const directory='/data/local/tmp/anshin-adb';
shell('mkdir -p '+quote(directory));
// Stop only a prior helper whose recorded PID still belongs to this exact named process.
const stop=`if [ -f ${directory}/agent.pid ]; then p=$(cat ${directory}/agent.pid); case "$p" in ''|*[!0-9]*) ;; *) n=$(cat /proc/$p/cmdline 2>/dev/null | tr '\\000' ' '); case "$n" in anshin-adb-helper*) kill "$p" ;; esac ;; esac; fi`;
shell(stop);
shell(`CLASSPATH=${quote(apkPath)} nohup app_process /system/bin --nice-name=anshin-adb-helper com.anshin.phone.AdbAgent >${directory}/agent.log 2>&1 </dev/null & echo $! >${directory}/agent.pid`);
let ready=false;
for(let i=0;i<25;i++){
 await new Promise(r=>setTimeout(r,300));
 try{const status=shell('content call --uri content://com.anshin.phone.adb-setup --method status');if(/ready=true/.test(status)){ready=true;break;}}catch{}
}
if(!ready){const detail=shell('tail -30 '+directory+'/agent.log');throw new Error('高级清理通道未就绪，未执行任何清理。\n'+detail);}
console.log(shell('content call --uri content://com.anshin.phone.adb-setup --method configure --extra '+quote('serverUrl:s:'+serverUrl)+' --extra prepared:b:true'));
console.log(adb('-s',serial,'shell','am','start','-W','-n','com.anshin.phone/.MainActivity'));
writeFileSync(resolve(logs,'last-setup.json'),JSON.stringify({at:new Date().toISOString(),sdk,serverUrl,publicService:!local,adbPrepared:true,cleaningPerformed:false,rootUsed:false},null,2)+'\n',{mode:0o600});
console.log('初始化完成：手机日常只需点“一键优化”。未删除任何已有软件或私人数据。');
