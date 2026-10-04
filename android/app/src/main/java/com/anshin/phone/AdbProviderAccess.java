package com.anshin.phone;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import java.lang.reflect.*;

/** The system content CLI's external-provider route: app_process has no AMS application record. */
final class AdbProviderAccess {
 static Bundle call(String authority,String method,String argument,Bundle extras)throws Exception{
  if(Process.myUid()!=2000)throw new SecurityException("只允许已授权ADB shell访问初始化provider。");
  if(!authority.equals(AdbSetupProvider.AUTHORITY)&&!authority.equals("settings"))throw new SecurityException("不允许的provider。");
  Object manager=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
  Class<?> am=Class.forName("android.app.IActivityManager");IBinder token=new Binder();Object holder=null;
  try{
   Method acquire=null;
   for(Method candidate:am.getMethods())if(candidate.getName().equals("getContentProviderExternal")&&(candidate.getParameterTypes().length==3||candidate.getParameterTypes().length==4)){acquire=candidate;break;}
   if(acquire==null)throw new IllegalStateException("系统未提供ADB外部provider接口。");
   holder=acquire.invoke(manager,acquire.getParameterTypes().length==3?new Object[]{authority,0,token}:new Object[]{authority,0,token,"anshin-adb"});
   if(holder==null)throw new IllegalStateException("初始化provider不存在。");
   Object provider=holder.getClass().getField("provider").get(holder);Class<?> contract=Class.forName("android.content.IContentProvider");
   Method invoke=null;for(Method candidate:contract.getMethods())if(candidate.getName().equals("call")){invoke=candidate;break;}
   if(invoke==null)throw new IllegalStateException("系统provider缺少call接口。");
   Class<?>[] types=invoke.getParameterTypes();Object[] parameters;
   if(types[0].getName().equals("android.content.AttributionSource")){
    Class<?> builder=Class.forName("android.content.AttributionSource$Builder");Object attribution=builder.getConstructor(int.class).newInstance(2000);builder.getMethod("setPackageName",String.class).invoke(attribution,"com.android.shell");Object source=builder.getMethod("build").invoke(attribution);parameters=new Object[]{source,authority,method,argument,extras};
   }else if(types.length==4)parameters=new Object[]{"com.android.shell",method,argument,extras};
   else if(types.length==5)parameters=new Object[]{"com.android.shell",authority,method,argument,extras};
   else if(types.length==6)parameters=new Object[]{"com.android.shell",null,authority,method,argument,extras};
   else throw new IllegalStateException("尚未适配此系统的provider协议。");
   return (Bundle)invoke.invoke(provider,parameters);
  }catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw error;}
  finally{
   if(holder!=null){
    Method release=null;for(Method candidate:am.getMethods())if(candidate.getName().equals("removeContentProviderExternalAsUser")&&candidate.getParameterTypes().length==3){release=candidate;break;}
    if(release!=null)release.invoke(manager,authority,token,0);
    else am.getMethod("removeContentProviderExternal",String.class,IBinder.class).invoke(manager,authority,token);
   }
  }
 }
 static String secureSetting(String name)throws Exception{Bundle extras=new Bundle();extras.putInt("_user",0);Bundle result=call("settings","GET_secure",name,extras);if(result==null)throw new IllegalStateException("读取系统默认应用失败。");return result.getString("value");}
 private AdbProviderAccess(){}
}
