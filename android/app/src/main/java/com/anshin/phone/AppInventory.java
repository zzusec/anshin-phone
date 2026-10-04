package com.anshin.phone;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AppInventory {
 private final Context context;
 public AppInventory(Context context){this.context=context;}
 private Set<String> runtimeCore(){
  Set<String> names=new HashSet<>();names.add(context.getPackageName());
  String ime=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
  if(ime!=null&&ime.contains("/"))names.add(ime.substring(0,ime.indexOf('/')));
  Intent home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
  ResolveInfo current=context.getPackageManager().resolveActivity(home,PackageManager.MATCH_DEFAULT_ONLY);
  if(current!=null&&current.activityInfo!=null)names.add(current.activityInfo.packageName);
  String sms=Telephony.Sms.getDefaultSmsPackage(context);if(sms!=null)names.add(sms);
  TelecomManager telecom=(TelecomManager)context.getSystemService(Context.TELECOM_SERVICE);
  if(telecom!=null&&telecom.getDefaultDialerPackage()!=null)names.add(telecom.getDefaultDialerPackage());
  for(Intent intent:new Intent[]{new Intent(Settings.ACTION_SETTINGS),new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)}){
   ResolveInfo activity=context.getPackageManager().resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY);
   if(activity!=null&&activity.activityInfo!=null)names.add(activity.activityInfo.packageName);
  }
  return names;
 }
 public JSONObject item(PackageInfo info) throws Exception {
  ApplicationInfo a=info.applicationInfo;if(a==null)throw new IllegalArgumentException("应用元数据不存在。");
  boolean system=(a.flags&ApplicationInfo.FLAG_SYSTEM)!=0;
  String category=PackagePolicy.category(info.packageName,a.uid,system,Build.MANUFACTURER,Build.VERSION.SDK_INT,runtimeCore());
  return new JSONObject().put("packageName",info.packageName).put("label",a.loadLabel(context.getPackageManager()).toString()).put("category",category).put("removable",PackagePolicy.removable(category)&&!AppWhitelist.contains(context,info.packageName)).put("system",system).put("whitelisted",AppWhitelist.contains(context,info.packageName)).put("versionName",info.versionName==null?"":info.versionName).put("reason",AppWhitelist.contains(context,info.packageName)?"常用软件，已保留":"core".equals(category)?"保护基本功能":"unknown".equals(category)?"系统依赖尚未确认":"");
 }
 public JSONArray scan() throws Exception {
  List<JSONObject> list=new ArrayList<>();
  for(PackageInfo p:context.getPackageManager().getInstalledPackages(0))if(p.applicationInfo!=null)list.add(item(p));
  list.sort(Comparator.comparing((JSONObject o)->!o.optBoolean("removable")).thenComparing(o->o.optString("label")));
  JSONArray result=new JSONArray();for(JSONObject item:list)result.put(item);return result;
 }
 public PackageInfo requireRemovable(String name) throws Exception {
  if(!PackagePolicy.validName(name))throw new SecurityException("无效的软件包名。");
  PackageInfo p=context.getPackageManager().getPackageInfo(name,0);
  if(!item(p).getBoolean("removable"))throw new SecurityException("核心组件或尚未确认的软件不能清理："+name);
  return p;
 }
 public boolean installed(String name){try{context.getPackageManager().getPackageInfo(name,0);return true;}catch(PackageManager.NameNotFoundException e){return false;}}
}
