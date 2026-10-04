package com.anshin.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.RemoteException;
import android.os.ParcelFileDescriptor;

/** ADB-only transport. Losing authorization never retries an operation via another privilege path. */
public final class AuthorizedShell {
 private static volatile Activity activity;
 private static volatile Runnable changed;
 private static final Runnable connectionChanged=()->{
  Activity current=activity;
  if(current!=null)current.runOnUiThread(()->{Runnable callback=changed;if(activity==current&&callback!=null)callback.run();});
 };
 public static void initialize(Activity a,Runnable callback){
  close();activity=a;changed=callback;AdbBridge.setListener(connectionChanged);
 }
 public static boolean ready(){return AdbBridge.ready();}
 public static void requestPermission(Activity a){
  if(ready()){connectionChanged.run();return;}
  new AlertDialog.Builder(a).setTitle("请家人连接电脑初始化")
   .setMessage("请用USB连接家人的电脑，开启USB调试，并在手机上允许这台电脑。其余初始化由电脑自动完成，无需安装或操作其他授权应用。本工具不会root。手机重启或授权失效后，请再次连接电脑初始化。")
   .setPositiveButton("知道了",null).show();
 }
 public static String uninstall(String name)throws RemoteException{
  IAuthorizedCleanup current=requireService();
  try{return current.uninstall(name);}catch(RemoteException e){throw disconnected(current,e);}
 }
 public static String restore(String name)throws RemoteException{
  IAuthorizedCleanup current=requireService();
  try{return current.restore(name);}catch(RemoteException e){throw disconnected(current,e);}
 }
 public static String installBackup(String packageName,ParcelFileDescriptor[] files,String[] names,long[] sizes)throws RemoteException{
  IAuthorizedCleanup current=requireService();
  try{return current.installBackup(packageName,files,names,sizes);}catch(RemoteException e){throw disconnected(current,e);}
 }
 private static IAuthorizedCleanup requireService(){
  IAuthorizedCleanup current=AdbBridge.getService();
  if(current==null)throw new IllegalStateException("高级清理连接失效，请家人通过USB连接电脑重新初始化。");
  return current;
 }
 private static RemoteException disconnected(IAuthorizedCleanup current,RemoteException cause){
  AdbBridge.invalidate(current,"高级清理Binder调用失败；未重试或切换权限通道",cause);
  RemoteException error=new RemoteException("高级清理连接失效，操作未重试。请连接电脑重新初始化："+cause.getMessage());error.initCause(cause);return error;
 }
 // Activity lifetime is not helper lifetime: do not invoke destroy or remove the registered Binder.
 public static void close(){AdbBridge.clearListener(connectionChanged);activity=null;changed=null;}
 private AuthorizedShell(){}
}
