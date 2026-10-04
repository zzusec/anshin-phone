package com.anshin.phone;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.io.IOException;
import java.util.NoSuchElementException;

/** Same APK, launched once by USB ADB using app_process; no TCP listener or arbitrary shell API. */
public final class AdbAgent {
 private static final String TAG="AnshinAdbAgent";
 // Total budget, including startup failures and app-process restarts. No subprocess is relaunched.
 static final int MAX_REGISTRATION_ATTEMPTS=4;
 static long retryDelayMillis(int attempts){
  if(attempts<1||attempts>=MAX_REGISTRATION_ATTEMPTS)throw new IllegalStateException("ADB重新注册次数已用尽，请连接电脑重新初始化。");
  return 1000L<<(attempts-1);
 }
 static void requireShell(int uid){AdbSetupProvider.requireShell(uid);}

 public static void main(String[] args){
  try{
   requireShell(Process.myUid());
   if(args.length!=0)throw new IllegalArgumentException("ADB helper不接受命令参数。");
   if(Looper.myLooper()==null)Looper.prepareMainLooper();
   Context context=shellContext();
   AuthorizedCleanupService service=new AuthorizedCleanupService(context);
   new Registration(context,service,new Handler(Looper.getMainLooper())).run();
   Looper.loop();
   throw new IllegalStateException("ADB helper Looper意外停止。");
  }catch(Exception e){Log.e(TAG,"ADB helper启动失败",e);System.err.println("ADB helper启动失败："+e);System.exit(1);}
 }
 private static Context shellContext()throws Exception{
  Class<?> activityThread=Class.forName("android.app.ActivityThread");
  Object thread=activityThread.getMethod("systemMain").invoke(null);
  Context system=(Context)activityThread.getMethod("getSystemContext").invoke(thread);
  // System context identifies as android (UID 1000), which must not be sent by UID 2000.
  Context shell=system.createPackageContext("com.android.shell",Context.CONTEXT_IGNORE_SECURITY);
  if(!"com.android.shell".equals(shell.getPackageName())||shell.getApplicationInfo().uid!=2000)
   throw new SecurityException("无法获得UID 2000的shell Context。");
  return shell;
 }

 private static final class Registration implements Runnable {
  private final Context context;
  private final AuthorizedCleanupService service;
  private final Handler handler;
  private int attempts;
  private IBinder lifetime;
  private IBinder.DeathRecipient death;
  Registration(Context context,AuthorizedCleanupService service,Handler handler){this.context=context;this.service=service;this.handler=handler;}
  @Override public void run(){
   attempts++;
   try{
    Bundle extras=new Bundle();extras.putBinder(AdbSetupProvider.KEY_BINDER,service);
    extras.putInt(AdbSetupProvider.KEY_APP_UID,service.targetUid());
    extras.putInt(AdbSetupProvider.KEY_HELPER_UID,Process.myUid());
    Bundle result;
    // Never hold a stable provider reference: its process death must not kill this helper.
    result=AdbProviderAccess.call(AdbSetupProvider.AUTHORITY,"register",null,extras);
    if(result==null||!result.getBoolean(AdbSetupProvider.KEY_READY)||result.getInt(AdbSetupProvider.KEY_PROTOCOL)!=AdbBridge.PROTOCOL_VERSION)
     throw new SecurityException("初始化provider协议握手失败。");
    IBinder token=result.getBinder(AdbSetupProvider.KEY_LIFETIME);
    if(token==null||!token.isBinderAlive())throw new IOException("手机助手进程在注册时退出。");
    IBinder.DeathRecipient recipient=()->handler.post(()->onAppDeath(token));
    token.linkToDeath(recipient,0);
    lifetime=token;death=recipient;
    Log.i(TAG,"ADB helper已注册，attempt="+attempts+"/"+MAX_REGISTRATION_ATTEMPTS);
   }catch(SecurityException|IllegalArgumentException|IllegalStateException e){fail(e);}
   catch(Exception e){Log.e(TAG,"ADB helper注册失败",e);retry(e);}
  }
  private void onAppDeath(IBinder token){
   if(lifetime!=token)return;
   try{token.unlinkToDeath(death,0);}catch(NoSuchElementException e){Log.w(TAG,"应用进程死亡监听已移除",e);}
   lifetime=null;death=null;
   Log.w(TAG,"手机助手进程已退出，将受控重新注册");retry(null);
  }
  private void retry(Throwable reason){
   if(attempts>=MAX_REGISTRATION_ATTEMPTS){fail(new IllegalStateException("ADB注册次数已用尽，请连接电脑重新初始化。",reason));return;}
   if(!handler.postDelayed(this,retryDelayMillis(attempts)))fail(new IllegalStateException("无法调度ADB重新注册。",reason));
  }
  private void fail(Throwable error){Log.e(TAG,"ADB helper已停止",error);System.err.println("ADB helper已停止："+error);System.exit(1);}
 }
 private AdbAgent(){}
}
