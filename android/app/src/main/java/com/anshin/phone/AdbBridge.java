package com.anshin.phone;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import java.util.NoSuchElementException;

/** Process-local connection to the shell helper; never launches a process or retries an action. */
public final class AdbBridge {
 static final int PROTOCOL_VERSION=3;
 private static final String TAG="AnshinAdbBridge";
 private static final Object LOCK=new Object();
 private static IAuthorizedCleanup service;
 private static IBinder.DeathRecipient death;
 private static volatile Runnable listener;

 static void register(IBinder binder)throws RemoteException{
  if(binder==null||!binder.pingBinder())throw new IllegalArgumentException("ADB helper Binder不可用。");
  IAuthorizedCleanup candidate=IAuthorizedCleanup.Stub.asInterface(binder);
  if(candidate.protocolVersion()!=PROTOCOL_VERSION)throw new SecurityException("ADB helper协议版本不兼容，请由电脑重新初始化。");
  synchronized(LOCK){
   if(service!=null&&service.asBinder().isBinderAlive()){
    if(service.asBinder().equals(binder))return;
    throw new IllegalStateException("已有ADB helper连接；拒绝重复启动。");
   }
   unlink(service,death);
   IBinder.DeathRecipient recipient=()->invalidate(candidate,"ADB helper进程已退出",null);
   binder.linkToDeath(recipient,0);
   if(!binder.isBinderAlive()){
    unlink(candidate,recipient);
    throw new RemoteException("ADB helper在注册期间退出。");
   }
   service=candidate;death=recipient;
  }
  Log.i(TAG,"ADB helper已连接，protocol="+PROTOCOL_VERSION);
  notifyChanged();
 }

 public static boolean ready(){return getService()!=null;}
 public static IAuthorizedCleanup getService(){
  IAuthorizedCleanup current;
  synchronized(LOCK){current=service;}
  if(current!=null&&!current.asBinder().isBinderAlive()){
   invalidate(current,"ADB helper Binder已失效",null);return null;
  }
  return current;
 }

 static void invalidate(IAuthorizedCleanup expected,String reason,Throwable error){
  IBinder.DeathRecipient recipient;
  synchronized(LOCK){
   if(service==null||service!=expected)return;
   recipient=death;service=null;death=null;
  }
  unlink(expected,recipient);
  if(error==null)Log.w(TAG,reason);else Log.e(TAG,reason,error);
  notifyChanged();
 }
 private static void unlink(IAuthorizedCleanup old,IBinder.DeathRecipient recipient){
  if(old==null||recipient==null)return;
  try{old.asBinder().unlinkToDeath(recipient,0);}
  catch(NoSuchElementException e){Log.w(TAG,"死亡监听已移除",e);}
 }
 static void setListener(Runnable callback){listener=callback;}
 static void clearListener(Runnable callback){if(listener==callback)listener=null;}
 private static void notifyChanged(){
  Runnable callback=listener;
  if(callback!=null)try{callback.run();}catch(RuntimeException e){Log.e(TAG,"连接状态通知失败",e);}
 }
 private AdbBridge(){}
}
