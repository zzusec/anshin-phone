package com.anshin.phone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import org.json.JSONArray;

/** Only the authorized USB/ADB shell may register a helper or read its minimal status. */
public final class AdbSetupProvider extends ContentProvider {
 static final String AUTHORITY="com.anshin.phone.adb-setup";
 static final String KEY_BINDER="binder",KEY_APP_UID="appUid",KEY_HELPER_UID="helperUid";
 static final String KEY_READY="ready",KEY_PROTOCOL="protocol",KEY_LIFETIME="lifetime";
 private final IBinder lifetime=new Binder();

 @Override public boolean onCreate(){Log.i("AnshinAdbSetup","ADB setup provider已创建");return true;}
 static void requireShell(int callerUid){
  if(callerUid!=2000)throw new SecurityException("初始化仅允许ADB shell UID 2000；不接受普通应用或root。");
 }
 static void requireTarget(int helperUid,int targetUid,int localUid){
  if(helperUid!=2000||targetUid<10000||targetUid>=100000||targetUid!=localUid)
   throw new SecurityException("ADB helper身份或目标应用UID不匹配；仅支持机主用户0。");
 }
 @Override public Bundle call(String method,String arg,Bundle extras){
  requireShell(Binder.getCallingUid());
  if(arg!=null)throw new IllegalArgumentException("ADB setup不接受额外参数。");
  if("status".equals(method)){
   if(extras!=null&&!extras.isEmpty())throw new IllegalArgumentException("status不接受附加数据。");
   return status();
  }
  if("policy".equals(method)){
   if(extras!=null&&!extras.isEmpty())throw new IllegalArgumentException("policy不接受附加数据。");
   Bundle result=new Bundle();result.putString("whitelist",AppWhitelist.read(getContext()).toString());return result;
  }
  if("configure".equals(method))return configure(extras);
  if(!"register".equals(method))throw new UnsupportedOperationException("不支持的ADB setup方法。");
  if(extras==null)throw new IllegalArgumentException("缺少ADB helper注册数据。");
  requireTarget(extras.getInt(KEY_HELPER_UID,-1),extras.getInt(KEY_APP_UID,-1),Process.myUid());
  try{AdbBridge.register(extras.getBinder(KEY_BINDER));}
  catch(RemoteException e){Log.e("AnshinAdbSetup","ADB helper注册失败",e);throw new IllegalStateException("ADB helper握手失败。",e);}
  Bundle result=status();result.putBinder(KEY_LIFETIME,lifetime);return result;
 }
 private Bundle configure(Bundle extras){
  if(extras==null)throw new IllegalArgumentException("缺少电脑初始化配置。");
  try{
   String server=extras.containsKey("serverUrl")?RemoteClient.validateBase(requiredString(extras,"serverUrl")):null;
   JSONArray whitelist=extras.containsKey("whitelist")?new JSONArray(requiredString(extras,"whitelist")):null;
   if(extras.containsKey("prepared")&&!(extras.get("prepared") instanceof Boolean))throw new IllegalArgumentException("prepared必须是boolean。");
   if(whitelist!=null)AppWhitelist.save(getContext(),whitelist);
   android.content.SharedPreferences.Editor editor=getContext().getSharedPreferences("anshin",0).edit();
   if(server!=null)editor.putString("serverUrl",server);
   if(extras.containsKey("prepared"))editor.putBoolean("adbPrepared",extras.getBoolean("prepared"));
   if(!editor.commit())throw new IllegalStateException("电脑初始化配置未能保存。");
   Log.i("AnshinAdbSetup","电脑初始化配置已保存");return status();
  }catch(RuntimeException e){Log.e("AnshinAdbSetup","电脑初始化配置失败",e);throw e;}
  catch(Exception e){Log.e("AnshinAdbSetup","电脑初始化配置失败",e);throw new IllegalArgumentException("电脑初始化配置无效。",e);}
 }
 private static String requiredString(Bundle extras,String key){
  Object value=extras.get(key);
  if(!(value instanceof String))throw new IllegalArgumentException(key+"必须是字符串。");
  return (String)value;
 }
 private static Bundle status(){
  Bundle result=new Bundle();result.putBoolean(KEY_READY,AdbBridge.ready());
  result.putInt(KEY_PROTOCOL,AdbBridge.PROTOCOL_VERSION);return result;
 }
 private static UnsupportedOperationException noCrud(){return new UnsupportedOperationException("ADB setup不提供CRUD或文件访问。");}
 @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){throw noCrud();}
 @Override public String getType(Uri uri){throw noCrud();}
 @Override public Uri insert(Uri uri,ContentValues values){throw noCrud();}
 @Override public int delete(Uri uri,String selection,String[] selectionArgs){throw noCrud();}
 @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){throw noCrud();}
}
