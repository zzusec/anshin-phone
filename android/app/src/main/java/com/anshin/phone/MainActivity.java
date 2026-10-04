package com.anshin.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
 private static final int UNINSTALL_REQUEST=100,VPN_REQUEST=101;
 private static final String PENDING_RESTORE="pendingRestore",DEFERRED_INSTALL="deferredInstallResult";
 static MainActivity active;
 private WebView web;
 private SharedPreferences prefs;
 private AppInventory apps;
 private BackupStore backups;
 private final ExecutorService io=Executors.newSingleThreadExecutor();
 private final Handler ui=new Handler(Looper.getMainLooper());
 private JSONArray inventory=new JSONArray(),history=new JSONArray(),taskResults=new JSONArray();
 private JSONObject remote,remoteTask;
 private final ArrayDeque<String> queue=new ArrayDeque<>();
 private final Set<String> prompted=new HashSet<>();
 private boolean foreground=false,cleaning=false,polling=false,optimizing=false,restoring=false;
 private final ArrayDeque<String> restoreQueue=new ArrayDeque<>();
 private int restoreTotal=0,restoreCompleted=0,restoreFailed=0;
 private String restoreCurrent="";
 private long generation=0,activeRun=0;
 private String currentPackage,currentLabel,remoteError="";
 private final Runnable pollLoop=new Runnable(){public void run(){if(foreground&&remote!=null&&!polling){polling=true;io.execute(()->{try{pollRemote();}catch(Exception e){remoteError=message(e);}finally{polling=false;}});}if(foreground)ui.postDelayed(this,3000);}};

 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);prefs=getSharedPreferences("anshin",MODE_PRIVATE);apps=new AppInventory(this);backups=new BackupStore(this);
  if(!prefs.contains("serverUrl"))prefs.edit().putString("serverUrl",BuildConfig.DEFAULT_SERVER_URL).apply();
  try{history=new JSONArray(prefs.getString("history","[]"));inventory=new JSONArray(prefs.getString("inventory","[]"));String r=prefs.getString("remote","");if(!r.isEmpty())remote=new JSONObject(r);}catch(JSONException e){throw new IllegalStateException("本地记录损坏，不能静默丢弃。",e);}
  web=new WebView(this);setContentView(web);web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
  if(BuildConfig.DEBUG)WebView.setWebContentsDebuggingEnabled(true);
  WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setTextZoom(Math.round(100*getResources().getConfiguration().fontScale));
  CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !trusted(r.getUrl());}
   @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
    Uri u=r.getUrl();if(!trusted(u))return denied();String path=u.getPath();if(path==null||path.contains("..")||path.contains("\\"))return denied();
    if("/".equals(path))path="/index.html";
    String mime=path.endsWith(".js")?"text/javascript":path.endsWith(".css")?"text/css":path.endsWith(".svg")?"image/svg+xml":"text/html";
    try{return new WebResourceResponse(mime,"UTF-8",200,"OK",assetHeaders(),getAssets().open(path.substring(1)));}catch(IOException e){return denied();}
   }
  });
  web.addJavascriptInterface(new Bridge(),"AnshinNative");web.loadUrl("https://appassets.anshin.local/index.html");
  AuthorizedShell.initialize(this,()->sendEvent("native-state",new JSONObject()));
  io.execute(()->{try{RuleRefreshJobService.schedule(this);}catch(Exception e){getSharedPreferences("anshin",0).edit().putString("ruleScheduleError",message(e)).apply();}});
 }
 private Map<String,String> assetHeaders(){Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");headers.put("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; connect-src 'none'");return headers;}
 private boolean trusted(Uri u){return "https".equals(u.getScheme())&&"appassets.anshin.local".equals(u.getHost())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443);}
 private WebResourceResponse denied(){return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
 private String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
 private void sendEvent(String name,JSONObject data){ui.post(()->{if(web!=null){String json=data.toString().replace("\u2028","\\u2028").replace("\u2029","\\u2029");web.evaluateJavascript("window.dispatchEvent(new CustomEvent("+JSONObject.quote(name)+",{detail:"+json+"}))",null);}});}
 private final class Bridge {
  @JavascriptInterface public void call(String raw){io.execute(()->{JSONObject response=new JSONObject();try{
   if(raw==null||raw.length()>131072)throw new IllegalArgumentException("请求过大。");JSONObject request=new JSONObject(raw);String id=request.getString("id");if(!id.matches("r[0-9]{1,12}"))throw new IllegalArgumentException("无效请求标识。");response.put("id",id);JSONObject result=handle(request.getString("action"),request.optJSONObject("data"));response.put("ok",true).put("result",result);
  }catch(Exception e){try{response.put("ok",false).put("error",message(e));}catch(JSONException impossible){throw new IllegalStateException(impossible);}}sendEvent("native-result",response);});}
 }
 private JSONObject handle(String action,JSONObject d)throws Exception{
  if(d==null)d=new JSONObject();final JSONObject data=d;
  switch(action){
   case "getState":return getState();
   case "rules:update":RuleListStore.refresh(this);DnsProtectionService.reloadRulesIfRunning(this);break;
   case "open-release":ui.post(()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/zzusec/anshin-phone/releases"))));break;
   case "update:check":new AppUpdater(this).check();break;
   case "update:install":{Intent installer=new AppUpdater(this).downloadAndPrepare();ui.post(()->startActivity(installer));break;}
   case "optimize":optimize();break;
   case "whitelist:save":AppWhitelist.save(this,d.getJSONArray("packages"));inventory=apps.scan();prefs.edit().putString("inventory",inventory.toString()).apply();syncInventory();break;
   case "scan":inventory=apps.scan();prefs.edit().putLong("scannedAt",System.currentTimeMillis()).putString("inventory",inventory.toString()).apply();syncInventory();break;
   case "uninstall":if(!d.optBoolean("acknowledgeDataLoss"))throw new SecurityException("未确认目标应用数据丢失。");beginCleanup(d.getJSONArray("packages"),null,false);break;
   case "restore":startRestoration(Collections.singletonList(d.getString("packageName")));break;
   case "restore-all":{List<String> names=new ArrayList<>();JSONArray removed=removedApps();for(int i=0;i<removed.length();i++)if(removed.getJSONObject(i).optBoolean("restorable"))names.add(removed.getJSONObject(i).getString("packageName"));startRestoration(names);break;}
   case "vpn":vpn(d.getBoolean("enabled"));break;
   case "settings":openSettings(d.getString("screen"));break;
   case "config:save":if(remote!=null)throw new IllegalStateException("先断开当前协助再修改服务地址。");prefs.edit().putString("serverUrl",RemoteClient.validateBase(d.getString("serverUrl"))).apply();break;
   case "rules:save":saveRules(d.getJSONArray("blockedDomains"),d.getJSONArray("allowedDomains"));break;
   case "remote:create":createRemote();break;
   case "remote:approve":requireRemote();callRemote("/approve","POST",new JSONObject().put("approve",true));pollRemote();break;
   case "remote:revoke":revokeRemote();break;
   case "remote:execute":executeRemote(d.getString("commandId"),d.getBoolean("approved"));break;
   case "share":String text=d.getString("text");if(remote==null||!text.equals(remote.optString("inviteUrl")))throw new SecurityException("仅允许分享当前协助邀请。");ui.post(()->startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"请帮我整理手机，打开链接后我会在手机上确认：\n"+text),"分享给家人")));break;
   case "shizuku":ui.post(()->AuthorizedShell.requestPermission(this));break;
   default:throw new SecurityException("未开放的操作。");
  }
  return new JSONObject().put("state",getState());
 }
 private long remoteExpiresAt(){return RemoteDto.parseExpiresAt(remote==null?null:remote.opt("expiresAt"));}
 private synchronized JSONObject getState()throws Exception{
  JSONArray blocked=prefs.contains("blockedDomains")?new JSONArray(prefs.getString("blockedDomains","[]")):new JSONArray(DomainRules.BASIC_BLOCKED_DOMAINS);
  JSONObject r=null;
  if(remote!=null){long expiresAtMs=remoteExpiresAt();if(expiresAtMs<=0||expiresAtMs<System.currentTimeMillis()){remote=null;persistRemote();}else{String phase=remote.optString("phase","created");r=new JSONObject().put("phase",phase).put("childName",remote.optString("childName","")).put("expiresAt",remote.optString("expiresAt"));if(!"claimed".equals(phase)&&!"approved".equals(phase))r.put("inviteUrl",remote.optString("inviteUrl"));}}
  return new JSONObject().put("deviceName",Build.MANUFACTURER+" "+Build.MODEL).put("androidVersion",Build.VERSION.RELEASE).put("inventory",inventory).put("scannedAt",prefs.getLong("scannedAt",0)==0?JSONObject.NULL:prefs.getLong("scannedAt",0)).put("history",history).put("vpn",DnsProtectionService.status(this)).put("blockedDomains",blocked).put("allowedDomains",new JSONArray(prefs.getString("allowedDomains","[]"))).put("capabilities",new JSONObject().put("shizuku",AuthorizedShell.ready()).put("adb",AdbBridge.ready())).put("serverUrl",prefs.getString("serverUrl","")).put("remote",r==null?JSONObject.NULL:r).put("remoteError",remoteError).put("cleaning",cleaning).put("rulesPendingRestart",prefs.getBoolean("rulesPendingRestart",false)).put("optimizing",optimizing).put("prepared",prefs.getBoolean("adbPrepared",false)).put("whitelist",AppWhitelist.display(this,inventory)).put("ruleUpdate",RuleListStore.status(this)).put("update",new AppUpdater(this).status()).put("removedApps",removedApps()).put("restoreProgress",new JSONObject().put("running",restoring).put("total",restoreTotal).put("completed",restoreCompleted).put("failed",restoreFailed).put("current",restoreCurrent)).put("lastOptimization",prefs.contains("lastOptimization")?new JSONObject(prefs.getString("lastOptimization","{}")):JSONObject.NULL);
 }
 private void saveOptimization(int removed,int failed,String text)throws Exception{
  prefs.edit().putString("lastOptimization",new JSONObject().put("time",System.currentTimeMillis()).put("removed",removed).put("failed",failed).put("summary",text).toString()).apply();
 }
 /** One daily action; a preconfigured editable whitelist prevents personal software loss. */
 private void optimize()throws Exception{
  if(cleaning||optimizing||restoring)throw new IllegalStateException("正在整理，请稍等。");
  inventory=apps.scan();prefs.edit().putLong("scannedAt",System.currentTimeMillis()).putString("inventory",inventory.toString()).apply();
  autoStartProtection();
  if(!prefs.getBoolean("adbPrepared",false)||!AuthorizedShell.ready()){
   saveOptimization(0,1,"手机已检查。清理功能需要连接电脑完成一次配置。");return;
  }
  JSONArray packages=new JSONArray();
  for(int i=0;i<inventory.length();i++){JSONObject item=inventory.getJSONObject(i);if(item.getBoolean("removable"))packages.put(item.getString("packageName"));}
  if(packages.length()==0){saveOptimization(0,0,"没有需要清理的软件，常用软件已保留。");return;}
  optimizing=true;
  try{beginCleanup(packages,null,false);}catch(Exception error){optimizing=false;throw error;}
 }
 private void autoStartProtection(){
  if(!prefs.getBoolean("adbPrepared",false))return;
  JSONObject status=DnsProtectionService.status(this);
  if(status.optBoolean("active")||status.optBoolean("starting"))return;
  if(VpnService.prepare(this)!=null){prefs.edit().putString("vpnError","广告防护尚未完成配置，请连接电脑处理。").apply();return;}
  ConnectivityManager manager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
  for(Network network:manager.getAllNetworks()){NetworkCapabilities caps=manager.getNetworkCapabilities(network);if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){prefs.edit().putString("vpnError","已有其他网络工具在使用，不会替换你的连接。").apply();return;}}
  ui.post(this::startDns);
 }
 private void saveRules(JSONArray blocks,JSONArray allows)throws Exception{
  if(blocks.length()>2000||allows.length()>2000)throw new IllegalArgumentException("每个名单最多2000条域名。");
  List<String>b=new ArrayList<>(),a=new ArrayList<>();for(int i=0;i<blocks.length();i++)b.add(blocks.getString(i));for(int i=0;i<allows.length();i++)a.add(allows.getString(i));
  DomainRules rules=new DomainRules(b,a);boolean active=DnsProtectionService.status(this).optBoolean("active");
  prefs.edit().putString("blockedDomains",new JSONArray(rules.getBlockedDomains()).toString()).putString("allowedDomains",new JSONArray(rules.getAllowedDomains()).toString()).putBoolean("rulesPendingRestart",active).apply();
 }
 private void vpn(boolean enabled)throws Exception{
  if(!enabled){prefs.edit().putBoolean("rulesPendingRestart",false).apply();startService(new Intent(this,DnsProtectionService.class).setAction(DnsProtectionService.ACTION_STOP));return;}
  ConnectivityManager manager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
  for(Network n:manager.getAllNetworks()){NetworkCapabilities c=manager.getNetworkCapabilities(n);if(c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&!DnsProtectionService.status(this).optBoolean("active"))throw new IllegalStateException("已有其他VPN正在使用。不会替换它，请先自行决定是否断开。");}
  ui.post(()->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},200);Intent consent=VpnService.prepare(this);if(consent!=null)startActivityForResult(consent,VPN_REQUEST);else startDns();});
 }
 private void startDns(){prefs.edit().putBoolean("rulesPendingRestart",false).apply();startForegroundService(new Intent(this,DnsProtectionService.class).setAction(DnsProtectionService.ACTION_START));}
 private void openSettings(String screen)throws Exception{
  String action;
  switch(screen){case "notification-settings":action=Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS;break;case "overlay-settings":action=Settings.ACTION_MANAGE_OVERLAY_PERMISSION;break;case "unknown-sources":action=Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES;break;case "accessibility-settings":action=Settings.ACTION_ACCESSIBILITY_SETTINGS;break;case "admin-settings":action="android.settings.DEVICE_ADMIN_SETTINGS";break;default:throw new SecurityException("不允许的系统设置页。");}
  Intent intent=new Intent(action);if(intent.resolveActivity(getPackageManager())==null)throw new IllegalStateException("这台手机没有提供该设置入口，请在系统设置中手动查找。");ui.post(()->startActivity(intent));
 }
 /** 入队但不在报告前启动执行；远程任务必须先成功上报 running，再开始第一包。 */
 private void beginCleanup(JSONArray packages,JSONObject command,boolean reportRunning)throws Exception{
  if(cleaning||restoring)throw new IllegalStateException("前一次处理仍未结束。");
  if(packages.length()<1||packages.length()>(command==null?1000:RemoteDto.MAX_PACKAGES_PER_COMMAND))throw new IllegalArgumentException("一次清理1至"+RemoteDto.MAX_PACKAGES_PER_COMMAND+"个软件。");
  Set<String> unique=new LinkedHashSet<>();for(int i=0;i<packages.length();i++){String name=packages.getString(i);if(!unique.add(name))throw new IllegalArgumentException("名单重复。");PackageInfo p=apps.requireRemovable(name);if((p.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)!=0&&!AuthorizedShell.ready())throw new IllegalStateException("预装应用需要高级清理授权。请先由子女设置Shizuku："+name);}
  taskResults=new JSONArray();remoteTask=command;for(String name:unique)queue.add(name);
  final long gen=++generation;activeRun=gen;cleaning=true;
  if(reportRunning){try{reportTask("running");}catch(Exception e){invalidateRun(gen,"协助状态上报失败，未开始清理。");throw e;}}
  io.execute(()->nextCleanup(gen));
 }
 /** 使一次已排队的执行代次失效：后续包不再执行，取消原因进入处理记录。 */
 private void invalidateRun(long gen,String text){
  if(gen!=generation)return;
  generation++;
  while(!queue.isEmpty()){String p=queue.remove();record(p,p,"rejected",text,false);}
  cleaning=false;remoteTask=null;currentPackage=null;currentLabel=null;
 }
 private void nextCleanup(long gen){
  if(gen!=generation||!cleaning){while(!queue.isEmpty()){String p=queue.remove();record(p,p,"rejected","协助已断开或已失效，未开始的项目已取消。",false);}finishCleanup();return;}
  if(queue.isEmpty()){finishCleanup();return;}
  currentPackage=queue.remove();
  try{
   PackageInfo p=apps.requireRemovable(currentPackage);currentLabel=p.applicationInfo.loadLabel(getPackageManager()).toString();backups.backup(p);
   if(AuthorizedShell.ready()){
    String output=AuthorizedShell.uninstall(currentPackage);boolean removed=!apps.installed(currentPackage);
    if(!output.trim().equals("Success")||!removed)throw new IOException("卸载未通过核验："+output.trim());
    record(currentPackage,currentLabel,"succeeded","已备份安装包，核验当前用户已卸载。",true);io.execute(()->nextCleanup(gen));
   }else{
    if(remoteTask!=null){try{reportTask("needs-confirmation");}catch(Exception e){remoteError="进度上报失败，系统确认仍会进行："+message(e);}}
    final String name=currentPackage;ui.post(()->startActivityForResult(new Intent(Intent.ACTION_UNINSTALL_PACKAGE,Uri.parse("package:"+name)).putExtra(Intent.EXTRA_RETURN_RESULT,true),UNINSTALL_REQUEST));
   }
  }catch(Exception e){record(currentPackage,currentLabel==null?currentPackage:currentLabel,"failed",message(e),false);io.execute(()->nextCleanup(gen));}
 }
 private synchronized void record(String name,String label,String status,String text,boolean restorable){recordAction("uninstall",name,label,status,text,restorable);}
 private synchronized void recordAction(String action,String name,String label,String status,String text,boolean restorable){
  try{JSONObject item=new JSONObject().put("action",action).put("packageName",name).put("label",label).put("status",status).put("message",text).put("restorable",restorable).put("time",System.currentTimeMillis());history.put(item);while(history.length()>200)history.remove(0);prefs.edit().putString("history",history.toString()).apply();if("uninstall".equals(action))taskResults.put(new JSONObject().put("packageName",name).put("status",status).put("message",text));}catch(JSONException e){throw new IllegalStateException(e);}
 }
 private JSONArray removedApps()throws Exception{
  JSONArray removed=new JSONArray();
  for(JSONObject entry:RemovalHistory.latestRemovals(history)){
   String name=entry.getString("packageName");if(apps.installed(name))continue;
   JSONObject item=new JSONObject().put("packageName",name).put("label",entry.optString("label",name)).put("removedAt",entry.optLong("time")).put("versionName","");
   try{JSONObject meta=backups.metadata(name);item.put("backupAvailable",true).put("restorable",true).put("versionName",String.valueOf(meta.optLong("version")));}
   catch(Exception error){item.put("backupAvailable",false).put("restorable",false).put("reason",message(error));}
   removed.put(item);
  }
  return removed;
 }
 private void startRestoration(List<String> names)throws Exception{
  if(cleaning||restoring||pendingRestore()!=null)throw new IllegalStateException("已有处理正在进行，请稍候。");
  if(names.isEmpty())throw new IllegalStateException("没有需要恢复的软件。");
  if(!AuthorizedShell.ready())throw new IllegalStateException("恢复通道未连接，请连接电脑完成初始化。");
  Set<String> allowed=new HashSet<>();JSONArray removed=removedApps();for(int i=0;i<removed.length();i++)if(removed.getJSONObject(i).optBoolean("restorable"))allowed.add(removed.getJSONObject(i).getString("packageName"));
  Set<String> unique=new LinkedHashSet<>(names);if(unique.size()!=names.size()||!allowed.containsAll(unique))throw new SecurityException("只能恢复本工具清理且有安装包备份的软件。");
  restoreQueue.addAll(unique);restoreTotal=unique.size();restoreCompleted=0;restoreFailed=0;restoring=true;
  io.execute(this::nextRestoration);
 }
 private void nextRestoration(){
  if(restoreQueue.isEmpty()){restoring=false;restoreCurrent="";sendEvent("native-navigate",json("route","history"));return;}
  String name=restoreQueue.remove();restoreCurrent=name;
  try{restoreBackup(name);}
  catch(Exception error){restoreFailed++;recordAction("restore",name,name,"failed",message(error),false);}
  finally{restoreCompleted++;}
  io.execute(this::nextRestoration);
 }
 private void restoreBackup(String name)throws Exception{
  JSONObject manifest=backups.manifest(name);if(apps.installed(name))throw new IllegalStateException("软件已安装，不执行覆盖。");
  String output;
  if(manifest.getBoolean("system"))output=AuthorizedShell.restore(name);
  else{
   JSONArray items=manifest.getJSONArray("files");android.os.ParcelFileDescriptor[] descriptors=new android.os.ParcelFileDescriptor[items.length()];String[] filenames=new String[items.length()];long[] sizes=new long[items.length()];
   try{
    for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);filenames[i]=item.getString("name");sizes[i]=item.getLong("bytes");descriptors[i]=android.os.ParcelFileDescriptor.open(backups.apkFile(name,filenames[i]),android.os.ParcelFileDescriptor.MODE_READ_ONLY);}
    output=AuthorizedShell.installBackup(name,descriptors,filenames,sizes);
   }finally{for(android.os.ParcelFileDescriptor descriptor:descriptors)if(descriptor!=null)descriptor.close();}
  }
  if((!manifest.getBoolean("system")&&!"Success".equals(output.trim()))||!apps.installed(name))throw new IOException("恢复后未通过安装状态核验。");
  String label=getPackageManager().getApplicationInfo(name,0).loadLabel(getPackageManager()).toString();recordAction("restore",name,label,"succeeded","软件已恢复；卸载前的聊天和账号数据不保证恢复。",false);
  inventory=apps.scan();prefs.edit().putString("inventory",inventory.toString()).apply();syncInventory();
 }
 private void finishCleanup(){
  try{inventory=apps.scan();prefs.edit().putString("inventory",inventory.toString()).apply();syncInventory();
   if(remoteTask!=null){int success=0,rejected=0;for(int i=0;i<taskResults.length();i++){String status=taskResults.getJSONObject(i).optString("status");if("succeeded".equals(status))success++;if("rejected".equals(status))rejected++;}
    reportTask(success==taskResults.length()?"succeeded":rejected==taskResults.length()&&rejected>0?"rejected":success==0?"failed":"partial");}
  }catch(Exception e){remoteError=message(e);}finally{cleaning=false;remoteTask=null;currentPackage=null;currentLabel=null;
   if(optimizing){
    try{int removed=0,failed=0;for(int i=0;i<taskResults.length();i++){String status=taskResults.getJSONObject(i).getString("status");if("succeeded".equals(status))removed++;else failed++;}
     saveOptimization(removed,failed,failed>0?"有些软件暂时没能清理，常用软件已保留。":removed>0?"已清理"+removed+"个名单外软件，常用软件已保留。":"没有需要清理的软件，常用软件已保留。");
    }catch(Exception error){remoteError=message(error);}
    optimizing=false;sendEvent("native-navigate",json("route","home"));
   }else sendEvent("native-navigate",json("route","history"));}
 }
 private JSONObject json(String key,String value){try{return new JSONObject().put(key,value);}catch(JSONException e){throw new IllegalStateException(e);}}
 private JSONObject pendingRestore(){try{String raw=prefs.getString(PENDING_RESTORE,"");if(raw.isEmpty())return null;JSONObject pending=new JSONObject(raw);if(System.currentTimeMillis()-pending.optLong("time")>10*60*1000){prefs.edit().remove(PENDING_RESTORE).apply();return null;}return pending;}catch(JSONException e){prefs.edit().remove(PENDING_RESTORE).apply();return null;}}
 private void restore(String name)throws Exception{
  if(cleaning)throw new IllegalStateException("先等待当前清理结束。");
  if(pendingRestore()!=null)throw new IllegalStateException("上一次恢复还没有得到系统结果，请稍候再试。");
  JSONObject manifest=backups.manifest(name);if(apps.installed(name))throw new IllegalStateException("应用已安装，不执行覆盖恢复。");
  if(manifest.getBoolean("system")){
   if(!AuthorizedShell.ready())throw new IllegalStateException("恢复预装注册需要Shizuku授权。");String output=AuthorizedShell.restore(name);if(!apps.installed(name))throw new IOException("恢复没有通过安装状态核验："+output);recordAction("restore",name,name,"succeeded","已恢复预装注册；不保证恢复应用数据。",false);inventory=apps.scan();prefs.edit().putString("inventory",inventory.toString()).apply();return;
  }
  if(!getPackageManager().canRequestPackageInstalls()){ui.post(()->startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()))));throw new IllegalStateException("请在系统页临时允许本工具安装备份，返回后再次点恢复，结束后可关闭授权。");}
  PackageInstaller installer=getPackageManager().getPackageInstaller();PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);params.setAppPackageName(name);int id=installer.createSession(params);
  String restoreId=java.util.UUID.randomUUID().toString();
  try(PackageInstaller.Session session=installer.openSession(id)){
   JSONArray files=manifest.getJSONArray("files");for(int i=0;i<files.length();i++){String f=files.getJSONObject(i).getString("name");File apk=backups.apkFile(name,f);try(InputStream in=new FileInputStream(apk);OutputStream out=session.openWrite(f,0,apk.length())){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);session.fsync(out);}}
   prefs.edit().putString(PENDING_RESTORE,new JSONObject().put("id",restoreId).put("packageName",name).put("time",System.currentTimeMillis()).toString()).apply();
   Intent callback=new Intent(this,InstallResultReceiver.class).setAction("com.anshin.phone.INSTALL_RESULT").putExtra("restoreId",restoreId).putExtra("packageName",name);
   int flags=android.app.PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=android.app.PendingIntent.FLAG_MUTABLE;
   session.commit(android.app.PendingIntent.getBroadcast(this,id,callback,flags).getIntentSender());
  }catch(Exception e){installer.abandonSession(id);prefs.edit().remove(PENDING_RESTORE).apply();throw e;}
 }
 void installResult(String restoreId,String name,int status,String text){io.execute(()->{
  JSONObject pending=pendingRestore();
  if(pending==null||!restoreId.equals(pending.optString("id"))||!name.equals(pending.optString("packageName")))return;
  boolean success=status==PackageInstaller.STATUS_SUCCESS&&apps.installed(name);
  recordAction("restore",name,name,success?"succeeded":"failed",success?"安装包已恢复，不保证恢复旧数据。":"恢复未完成："+text,false);
  prefs.edit().remove(PENDING_RESTORE).apply();
  try{inventory=apps.scan();prefs.edit().putString("inventory",inventory.toString()).apply();}catch(Exception e){remoteError=message(e);}
  sendEvent("native-navigate",json("route","history"));});}
 private void requireRemote()throws Exception{if(remote==null||remoteExpiresAt()<=System.currentTimeMillis())throw new IllegalStateException("远程协助已过期，请重新发起。");}
 private JSONObject callRemote(String suffix,String method,JSONObject body)throws Exception{requireRemote();return RemoteClient.request(prefs.getString("serverUrl",""),"/api/sessions/"+remote.getString("sessionId")+suffix,method,remote.getString("deviceToken"),body);}
 private void createRemote()throws Exception{
  if(remote!=null)throw new IllegalStateException("已有协助会话，请先断开。");if(!foreground)throw new IllegalStateException("请在手机助手前台发起协助。");
  inventory=apps.scan();prefs.edit().putLong("scannedAt",System.currentTimeMillis()).putString("inventory",inventory.toString()).apply();
  remote=RemoteClient.request(prefs.getString("serverUrl",""),"/api/sessions","POST",null,new JSONObject().put("deviceName",Build.MANUFACTURER+" "+Build.MODEL).put("inventory",RemoteDto.inventory(inventory)));
  if(RemoteDto.parseExpiresAt(remote.opt("expiresAt"))<=0){remote=null;throw new IOException("服务返回的会话时间无法解析，未建立协助。");}
  remote.put("phase","created");persistRemote();remoteError="";ui.post(()->getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
 }
 private synchronized void persistRemote(){prefs.edit().putString("remote",remote==null?"":remote.toString()).apply();}
 private void pollRemote()throws Exception{
  requireRemote();JSONObject update=callRemote("/device","GET",null);
  String expiresAt=update.optString("expiresAt","");
  if(RemoteDto.parseExpiresAt(expiresAt)<=0)throw new IOException("服务返回的会话时间无法解析。");
  remote.put("phase",update.getString("phase")).put("childName",update.optString("childName","")).put("expiresAt",expiresAt);persistRemote();remoteError="";
  if(!"approved".equals(update.getString("phase"))||cleaning)return;
  JSONArray commands=update.optJSONArray("commands");if(commands==null)return;
  for(int i=0;i<commands.length();i++){
   JSONObject c=commands.getJSONObject(i);String id=c.getString("id");if(!"pending".equals(c.optString("status"))||prompted.contains(id))continue;
   JSONArray names=c.getJSONArray("packages"),labels=new JSONArray();for(int j=0;j<names.length();j++){PackageInfo p=apps.requireRemovable(names.getString(j));labels.put(apps.item(p));}
   prompted.add(id);sendEvent("native-remote-task",new JSONObject().put("id",id).put("apps",labels));break;
  }
 }
 private void executeRemote(String id,boolean approved)throws Exception{
  JSONObject update=callRemote("/device","GET",null);if(!"approved".equals(update.getString("phase")))throw new SecurityException("协助权限已失效。");JSONObject found=null;JSONArray commands=update.getJSONArray("commands");for(int i=0;i<commands.length();i++)if(id.equals(commands.getJSONObject(i).getString("id")))found=commands.getJSONObject(i);
  if(found==null||!"pending".equals(found.optString("status"))||!prompted.contains(id))throw new SecurityException("清理请求不是当前手机已展示的任务。");
  if(!approved){JSONArray results=new JSONArray();for(int i=0;i<found.getJSONArray("packages").length();i++)results.put(new JSONObject().put("packageName",found.getJSONArray("packages").getString(i)).put("status","rejected").put("message","手机持有人拒绝了本次清理。"));callRemote("/commands/"+id+"/result","POST",new JSONObject().put("status","rejected").put("results",results));return;}
  try{beginCleanup(found.getJSONArray("packages"),found,true);}
  catch(Exception e){
   JSONArray results=new JSONArray();for(int i=0;i<found.getJSONArray("packages").length();i++)results.put(new JSONObject().put("packageName",found.getJSONArray("packages").getString(i)).put("status","failed").put("message",message(e)));
   try{callRemote("/commands/"+id+"/result","POST",new JSONObject().put("status","failed").put("results",results));}catch(Exception reportFailure){remoteError="清理任务未能开始，且状态上报失败："+message(reportFailure);}
   throw e;
  }
 }
 private void reportTask(String status)throws Exception{if(remoteTask!=null)callRemote("/commands/"+remoteTask.getString("id")+"/result","POST",new JSONObject().put("status",status).put("results",taskResults));}
 private void syncInventory()throws Exception{if(remote!=null&&"approved".equals(remote.optString("phase")))callRemote("/inventory","POST",new JSONObject().put("inventory",RemoteDto.inventory(inventory)));}
 /** 手机随时可撤销：立即取消未开始的包；进行中的系统操作不能强行中断，但下一包之前会停止。 */
 private void revokeRemote(){
  final JSONObject old;
  synchronized(this){
   old=remote;generation++;
   while(!queue.isEmpty()){String p=queue.remove();record(p,p,"rejected","协助已断开，未开始的项目已取消。",false);}
   remoteTask=null;prompted.clear();remote=null;remoteError="";
   persistRemote();
  }
  ui.post(()->getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
  if(old!=null){try{RemoteClient.request(prefs.getString("serverUrl",""),"/api/sessions/"+old.getString("sessionId"),"DELETE",old.getString("deviceToken"),null);}catch(Exception e){remoteError="手机已断开协助；通知服务的请求未完成，服务会话到期后自动失效。";}}
 }
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==VPN_REQUEST){if(result==RESULT_OK)startDns();else prefs.edit().putString("vpnError","未批准VPN授权，域名过滤未开启。").apply();}else if(request==UNINSTALL_REQUEST&&cleaning&&currentPackage!=null){final long gen=activeRun;final String name=currentPackage,label=currentLabel;io.execute(()->{boolean removed=!apps.installed(name);record(name,label,removed?"succeeded":"failed",removed?"系统卸载后已核验应用不在当前用户安装清单。":"系统未卸载此应用，可能取消或被设备管理权限阻止。",removed);nextCleanup(gen);});}}
 @Override protected void onResume(){super.onResume();active=this;foreground=true;ui.removeCallbacks(pollLoop);ui.post(pollLoop);if(remote!=null)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);processDeferredInstall();autoStartProtection();}
 private void processDeferredInstall(){try{String raw=prefs.getString(DEFERRED_INSTALL,"");if(raw.isEmpty())return;JSONObject deferred=new JSONObject(raw);prefs.edit().remove(DEFERRED_INSTALL).apply();installResult(deferred.optString("id"),deferred.optString("packageName"),deferred.optInt("status",PackageInstaller.STATUS_FAILURE),deferred.optString("message",""));}catch(JSONException e){prefs.edit().remove(DEFERRED_INSTALL).apply();}}
 @Override protected void onPause(){foreground=false;ui.removeCallbacks(pollLoop);super.onPause();}
 @Override protected void onDestroy(){foreground=false;ui.removeCallbacks(pollLoop);if(active==this)active=null;AuthorizedShell.close();if(web!=null){web.removeJavascriptInterface("AnshinNative");web.destroy();web=null;}io.shutdown();super.onDestroy();}
 @Override public void onBackPressed(){if(cleaning){new AlertDialog.Builder(this).setTitle("正在清理").setMessage("可以取消尚未开始的软件卸载，当前系统操作不能强行中断。").setNegativeButton("继续",null).setPositiveButton("取消后续",(d,w)->io.execute(()->{while(!queue.isEmpty()){String p=queue.remove();record(p,p,"rejected","用户取消了后续清理。",false);}})).show();}else if(web!=null)sendEvent("native-navigate",json("route","home"));else super.onBackPressed();}
}
