package com.anshin.phone;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

public final class AppWhitelist {
 public static JSONArray read(Context context){
  SharedPreferences p=context.getSharedPreferences("anshin",Context.MODE_PRIVATE);
  if(!p.contains("appWhitelist"))return new JSONArray(WhitelistPolicy.DEFAULTS.keySet());
  try{JSONArray values=new JSONArray(p.getString("appWhitelist","[]"));List<String> names=new ArrayList<>();for(int i=0;i<values.length();i++)names.add(values.getString(i));return new JSONArray(WhitelistPolicy.validate(names));}
  catch(Exception e){throw new IllegalStateException("常用软件名单损坏，已停止自动清理。",e);}
 }
 public static boolean contains(Context context,String name){JSONArray list=read(context);for(int i=0;i<list.length();i++)if(name.equals(list.optString(i)))return true;return false;}
 public static void save(Context context,JSONArray values)throws JSONException{
  List<String> names=new ArrayList<>();for(int i=0;i<values.length();i++)names.add(values.getString(i));
  Set<String> validated=WhitelistPolicy.validate(names);
  if(!context.getSharedPreferences("anshin",Context.MODE_PRIVATE).edit().putString("appWhitelist",new JSONArray(validated).toString()).commit())throw new IllegalStateException("保存常用软件名单失败。");
 }
 public static JSONArray display(Context context,JSONArray inventory)throws JSONException{
  JSONArray names=read(context),out=new JSONArray();Map<String,JSONObject> items=new LinkedHashMap<>();
  for(int i=0;i<inventory.length();i++){JSONObject item=inventory.getJSONObject(i);items.put(item.getString("packageName"),item);}
  for(int i=0;i<names.length();i++){
   String name=names.getString(i);JSONObject item=items.get(name);String label=item==null?WhitelistPolicy.DEFAULTS.get(name):item.optString("label");
   out.put(new JSONObject().put("packageName",name).put("label",label==null?name:label).put("kept",true).put("installed",item!=null));
  }
  for(JSONObject item:items.values())if(!contains(context,item.getString("packageName"))&&!"core".equals(item.optString("category")))out.put(new JSONObject().put("packageName",item.getString("packageName")).put("label",item.getString("label")).put("kept",false).put("installed",true));
  return out;
 }
 private AppWhitelist(){}
}
