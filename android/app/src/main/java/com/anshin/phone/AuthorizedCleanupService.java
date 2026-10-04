package com.anshin.phone;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Only constrained package-manager actions from the companion app, executed as shell, never root. */
public final class AuthorizedCleanupService extends IAuthorizedCleanup.Stub {
 private static final String TAG="AnshinCleanup";
 private final Context context;
 private final int appUid;
 public AuthorizedCleanupService(Context context)throws PackageManager.NameNotFoundException{
  this.context="com.android.shell".equals(context.getPackageName())?context:context.createPackageContext("com.android.shell",Context.CONTEXT_IGNORE_SECURITY);
  if(this.context.getApplicationInfo().uid!=2000)throw new SecurityException("执行端Context不是shell UID 2000。");
  appUid=this.context.getPackageManager().getApplicationInfo(PackagePolicy.SELF,0).uid;
  requireCaller(appUid,appUid,Process.myUid());
 }
 int targetUid(){return appUid;}
 static void requireCaller(int callerUid,int appUid,int helperUid){
  if(helperUid!=2000)throw new SecurityException("执行端必须是shell UID 2000，不接受root。");
  if(appUid<10000||appUid>=100000)throw new SecurityException("此版本只支持机主用户0的手机助手。");
  if(callerUid!=appUid)throw new SecurityException("仅允许手机助手UID调用。");
 }
 private void caller(){
  try{requireCaller(Binder.getCallingUid(),appUid,Process.myUid());}
  catch(SecurityException e){Log.w(TAG,"拒绝调用，uid="+Binder.getCallingUid(),e);throw e;}
 }
 @Override public int protocolVersion(){caller();return AdbBridge.PROTOCOL_VERSION;}

 // These checks also apply to installed siblings of a shared UID, not only the selected package.
 static void requireAllowed(String name,int uid,boolean system,String manufacturer,int sdk,Set<String> dynamic,boolean restore){
  if(!PackagePolicy.validName(name))throw new SecurityException("无效包名。");
  if(uid<10000||uid>=100000)throw new SecurityException("拒绝高权限UID或非机主用户的软件包。");
  String category=PackagePolicy.category(name,uid,system,manufacturer,sdk,dynamic);
  if(restore?!"optional".equals(category):!PackagePolicy.removable(category))
   throw new SecurityException(restore?"只允许恢复已适配的非核心预装注册。":"执行端拒绝核心或未确认包。");
 }
 private Set<String> runtimeCore()throws Exception{
  Set<String> names=new HashSet<>();names.add(PackagePolicy.SELF);
  String ime=AdbProviderAccess.secureSetting(Settings.Secure.DEFAULT_INPUT_METHOD);
  if(ime!=null&&ime.contains("/"))names.add(ime.substring(0,ime.indexOf('/')));
  protectResolved(names,new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
  String sms=AdbProviderAccess.secureSetting("sms_default_application");if(sms!=null)names.add(sms);
  TelecomManager telecom=(TelecomManager)context.getSystemService(Context.TELECOM_SERVICE);
  if(telecom!=null){String dialer=telecom.getDefaultDialerPackage();if(dialer!=null)names.add(dialer);}
  // Keep the execution boundary aligned with AppInventory, including settings and camera defaults.
  protectResolved(names,new Intent(Settings.ACTION_SETTINGS));
  protectResolved(names,new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
  return names;
 }
 private void protectResolved(Set<String> names,Intent intent){
  ResolveInfo resolved=context.getPackageManager().resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY);
  if(resolved!=null&&resolved.activityInfo!=null)names.add(resolved.activityInfo.packageName);
 }
 private void require(String name,boolean restore)throws Exception{
  if(!PackagePolicy.validName(name))throw new SecurityException("无效包名。");
  PackageManager pm=context.getPackageManager();
  PackageInfo target=pm.getPackageInfo(name,restore?PackageManager.MATCH_UNINSTALLED_PACKAGES:0);
  ApplicationInfo info=target.applicationInfo;
  if(info==null)throw new SecurityException("应用元数据缺失，拒绝操作。");
  Set<String> dynamic=runtimeCore(); // Re-query immediately before every operation, never trust the UI scan.
  requireAllowed(name,info.uid,(info.flags&ApplicationInfo.FLAG_SYSTEM)!=0,Build.MANUFACTURER,Build.VERSION.SDK_INT,dynamic,restore);
  String[] peers=pm.getPackagesForUid(info.uid);
  if(!restore&&(peers==null||peers.length==0))throw new SecurityException("无法核对软件包UID，拒绝卸载。");
  if(peers!=null)for(String peer:peers){
   ApplicationInfo sibling=pm.getApplicationInfo(peer,PackageManager.MATCH_UNINSTALLED_PACKAGES);
   if(sibling.uid!=info.uid)throw new SecurityException("共享UID在检查期间发生变化，拒绝操作。");
   requireAllowed(peer,sibling.uid,(sibling.flags&ApplicationInfo.FLAG_SYSTEM)!=0,Build.MANUFACTURER,Build.VERSION.SDK_INT,dynamic,false);
  }
 }
 static void requireNotWhitelisted(String name,String policy)throws JSONException{
  if(policy==null)throw new SecurityException("无法读取最新白名单，拒绝卸载。");
  JSONArray names=new JSONArray(policy);
  boolean listed=false;
  for(int i=0;i<names.length();i++){
   Object value=names.get(i);
   if(!(value instanceof String)||!PackagePolicy.validName((String)value))throw new SecurityException("白名单数据无效，拒绝卸载。");
   if(name.equals(value))listed=true;
  }
  if(listed)throw new SecurityException("软件在保留白名单中，拒绝卸载："+name);
 }
 private void requireNotWhitelisted(String name)throws Exception{
  Bundle policy=AdbProviderAccess.call(AdbSetupProvider.AUTHORITY,"policy",null,null);
  requireNotWhitelisted(name,policy==null?null:policy.getString("whitelist"));
 }
 static String[] uninstallCommand(String name){
  if(!PackagePolicy.validName(name))throw new SecurityException("无效包名。");
  return new String[]{"/system/bin/pm","uninstall","--user","0",name};
 }
 static String[] restoreCommand(String name){
  if(!PackagePolicy.validName(name))throw new SecurityException("无效包名。");
  return new String[]{"/system/bin/cmd","package","install-existing","--user","0",name};
 }
 @Override public synchronized String uninstall(String name)throws RemoteException{
  caller();long identity=Binder.clearCallingIdentity();
  try{require(name,false);requireNotWhitelisted(name);String output=command(uninstallCommand(name));Log.i(TAG,"用户0卸载命令完成："+name);return output;}
  catch(SecurityException e){Log.w(TAG,"卸载被执行策略拒绝："+name,e);throw e;}
  catch(Exception e){Log.e(TAG,"卸载失败："+name,e);throw new IllegalStateException("卸载失败："+name+"："+e.getMessage(),e);}
  finally{Binder.restoreCallingIdentity(identity);}
 }
 @Override public synchronized String restore(String name)throws RemoteException{
  caller();long identity=Binder.clearCallingIdentity();
  try{require(name,true);String output=command(restoreCommand(name));Log.i(TAG,"用户0预装注册命令完成："+name);return output;}
  catch(SecurityException e){Log.w(TAG,"恢复被执行策略拒绝："+name,e);throw e;}
  catch(Exception e){Log.e(TAG,"恢复失败："+name,e);throw new IllegalStateException("恢复失败："+name+"："+e.getMessage(),e);}
  finally{Binder.restoreCallingIdentity(identity);}
 }
 @Override public synchronized String installBackup(String name,ParcelFileDescriptor[] files,String[] names,long[] sizes)throws RemoteException{
  long identity=0;boolean cleared=false;File directory=null;int sessionId=-1;BackupInstallSpec spec=null;Exception failure=null;
  try{
   caller();identity=Binder.clearCallingIdentity();cleared=true;
   spec=new BackupInstallSpec(name,files,names,sizes);
   PackageManager pm=context.getPackageManager();requireBackupTarget(spec,pm,runtimeCore());
   File candidate=new File(BackupInstallSpec.TEMP_PARENT,BackupInstallSpec.TEMP_PREFIX+UUID.randomUUID());
   Os.mkdir(candidate.getAbsolutePath(),0700);directory=candidate; // Never clean a directory we did not create.
   for(int i=0;i<files.length;i++){
    ParcelFileDescriptor source=files[i];StructStat stat=Os.fstat(source.getFileDescriptor());
    if(!OsConstants.S_ISREG(stat.st_mode)||(Os.fcntlInt(source.getFileDescriptor(),OsConstants.F_GETFL,0)&OsConstants.O_ACCMODE)!=OsConstants.O_RDONLY)
     throw new SecurityException("恢复只接受只读普通APK文件描述符。");
    spec.requireCopiedSize(i,stat.st_size);Os.lseek(source.getFileDescriptor(),0,OsConstants.SEEK_SET);
    File apk=new File(directory,spec.names[i]);long copied=0;
    try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(source);FileOutputStream out=new FileOutputStream(apk)){
     byte[] buffer=new byte[65536];int count;
     while((count=in.read(buffer))!=-1){copied+=count;if(copied>spec.sizes[i])throw new IOException("恢复APK超过声明长度。");out.write(buffer,0,count);}
    }
    spec.requireCopiedSize(i,copied);
    try(ZipFile zip=new ZipFile(apk)){
     ZipEntry manifest=zip.getEntry("AndroidManifest.xml");
     if(manifest==null||manifest.isDirectory()||manifest.getSize()<=0)throw new IOException("恢复文件不是APK："+spec.names[i]);
    }
   }
   // Android 9's archive parser only collects certificates when GET_SIGNATURES is also set.
   PackageInfo archive=pm.getPackageArchiveInfo(new File(directory,"base.apk").getAbsolutePath(),PackageManager.GET_SIGNATURES|PackageManager.GET_SIGNING_CERTIFICATES);
   if(archive==null||archive.applicationInfo==null)throw new IOException("base.apk不是可解析的APK。");
   Set<String> core=runtimeCore();requireBackupMetadata(spec,archive,core);
   if(archive.signingInfo==null)throw new SecurityException("恢复APK缺少可验证签名。");
   Signature[] signers=archive.signingInfo.hasMultipleSigners()?archive.signingInfo.getApkContentsSigners():archive.signingInfo.getSigningCertificateHistory();
   if(signers==null||signers.length==0)throw new SecurityException("恢复APK签名信息为空。");
   for(Signature signer:signers)if(pm.hasSigningCertificate("android",signer.toByteArray(),PackageManager.CERT_INPUT_RAW_X509))
    spec.requirePackage(archive.packageName,archive.sharedUserId,true,core); // Platform certificates can confer signature permissions even outside /system.
   requireBackupTarget(spec,pm,core);
   sessionId=BackupInstallSpec.parseSessionId(command(spec.createCommand()));
   for(int i=0;i<files.length;i++)spec.requireWriteSuccess(i,command(spec.writeCommand(sessionId,i,directory)));
   // Roles can change while the descriptors are copied or the session is written.
   core=runtimeCore();requireBackupMetadata(spec,archive,core);requireBackupTarget(spec,pm,core);
   BackupInstallSpec.requireSuccess(command(BackupInstallSpec.commitCommand(sessionId)));
   PackageInfo installed=pm.getPackageInfo(name,0);requireBackupMetadata(spec,installed,runtimeCore());
   BackupInstallSpec.requireUid(installed.applicationInfo.uid);
   if((installed.applicationInfo.flags&ApplicationInfo.FLAG_INSTALLED)==0)throw new IOException("安装提交后未核验到本包已安装。");
  }catch(Exception e){failure=e;}
  finally{
   if(files!=null)for(ParcelFileDescriptor source:files)if(source!=null)try{source.close();}catch(Exception e){failure=cleanupError(failure,"关闭恢复描述符失败",e);}
   if(directory!=null){
    for(String file:spec.names)try{Files.deleteIfExists(new File(directory,file).toPath());}catch(Exception e){failure=cleanupError(failure,"清理恢复临时APK失败",e);}
    try{Files.delete(directory.toPath());}catch(Exception e){failure=cleanupError(failure,"清理恢复临时目录失败",e);}
   }
   if(failure!=null&&sessionId>0)try{BackupInstallSpec.requireSuccess(command(BackupInstallSpec.abandonCommand(sessionId)));}catch(Exception e){failure=cleanupError(failure,"放弃本次安装会话失败",e);}
   if(cleared)Binder.restoreCallingIdentity(identity);
  }
  if(failure!=null){
   Log.e(TAG,"APK恢复失败："+name,failure);String message="APK恢复失败："+name+"："+failure.getMessage();
   if(failure instanceof SecurityException)throw new SecurityException(message,failure);
   throw new IllegalStateException(message,failure);
  }
  Log.i(TAG,"用户0 APK恢复已核验："+name);return "Success";
 }
 private static void requireBackupMetadata(BackupInstallSpec spec,PackageInfo info,Set<String> core){
  if(info==null||info.applicationInfo==null)throw new SecurityException("恢复应用元数据缺失。");
  if(!spec.packageName.equals(info.applicationInfo.packageName))throw new SecurityException("恢复应用实际包名不匹配。");
  int flags=ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP|ApplicationInfo.FLAG_PERSISTENT;
  spec.requirePackage(info.packageName,info.sharedUserId,(info.applicationInfo.flags&flags)!=0,core);
 }
 private void requireBackupTarget(BackupInstallSpec spec,PackageManager pm,Set<String> core)throws Exception{
  spec.requirePackage(spec.packageName,null,false,core);
  PackageInfo existing;
  try{existing=pm.getPackageInfo(spec.packageName,PackageManager.MATCH_UNINSTALLED_PACKAGES);}
  catch(PackageManager.NameNotFoundException absent){return;} // No old registration is normal for a removed user APK.
  requireBackupMetadata(spec,existing,core);BackupInstallSpec.requireUid(existing.applicationInfo.uid);
  String[] peers=pm.getPackagesForUid(existing.applicationInfo.uid);
  if(peers!=null)for(String peer:peers)if(!spec.packageName.equals(peer))throw new SecurityException("拒绝覆盖共享UID的软件包。");
 }
 private static Exception cleanupError(Exception failure,String action,Exception cleanup){
  String message=(failure==null?"":failure.getMessage()+"；")+action+"："+cleanup.getMessage();
  Exception combined=failure instanceof SecurityException?new SecurityException(message,failure):new IOException(message,failure==null?cleanup:failure);
  if(failure!=null)combined.addSuppressed(cleanup);return combined;
 }
 private String command(String[] args)throws Exception{
  java.lang.Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
  FutureTask<String> read=new FutureTask<>(()->{
   try(InputStream in=process.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] buffer=new byte[1024];int count;
    while((count=in.read(buffer))!=-1){if(out.size()+count>16384)throw new IOException("命令响应异常过大。");out.write(buffer,0,count);}
    return out.toString("UTF-8");
   }
  });
  Thread reader=new Thread(read,"cleanup-command-output");reader.setDaemon(true);reader.start();Exception failure=null;
  try{
   if(!process.waitFor(25,TimeUnit.SECONDS))throw new IOException("包管理操作超时。");
   String output=read.get(2,TimeUnit.SECONDS);
   if(process.exitValue()!=0)throw new IOException(output.trim().isEmpty()?"包管理操作失败。":output.trim());
   return output;
  }catch(Exception e){failure=e;throw e;}
  finally{
   process.destroy();if(process.isAlive())process.destroyForcibly();
   try{process.getInputStream().close();}
   catch(IOException e){throw cleanupError(failure,"关闭包管理命令响应失败",e);}
   finally{read.cancel(true);}
  }
 }
 @Override public void destroy(){
  int uid=Binder.getCallingUid();
  if(Process.myUid()!=2000||(uid!=appUid&&uid!=2000))throw new SecurityException("拒绝停止ADB helper。");
  Log.i(TAG,"ADB helper显式停止，caller="+uid);System.exit(0);
 }
}
