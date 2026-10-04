package com.anshin.phone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 跨端远程契约的唯一映射点：中转服务只接受 packageName/label/category/removable/system/versionName，
 * 时间一律为 UTC ISO-8601 字符串，状态一律为 created/claimed/approved，每批任务最多 50 包。
 * 本地界面保留的 reason 等解释字段不进入远程清单。
 */
public final class RemoteDto {
 public static final int MAX_PACKAGES_PER_COMMAND=50;
 public static final int MAX_LABEL_LENGTH=200;
 public static final int MAX_VERSION_LENGTH=100;
 private RemoteDto(){}

 /** 去掉控制字符与首尾空白并截断；清洗后为空返回 null。 */
 public static String sanitizeText(String value,int max){
  if(value==null)return null;
  StringBuilder out=new StringBuilder(Math.min(value.length(),max));
  for(int i=0;i<value.length()&&out.length()<max;i++){char c=value.charAt(i);if(c>=0x20&&c!=0x7f)out.append(c);}
  String trimmed=out.toString().trim();
  return trimmed.isEmpty()?null:trimmed;
 }

 /** 扫描结果条目 → 远程清单条目；label 缺失时回退包名，服务端必填字段永远齐全。 */
 public static JSONObject inventoryItem(JSONObject scanned) throws JSONException {
  if(scanned==null)throw new IllegalArgumentException("应用条目缺失。");
  String name=optString(scanned,"packageName");
  if(!PackagePolicy.validInventoryName(name))throw new IllegalArgumentException("应用包名无效，不能上传远程清单。");
  String category=optString(scanned,"category");
  if(!"core".equals(category)&&!"optional".equals(category)&&!"user".equals(category)&&!"unknown".equals(category)){
   throw new IllegalArgumentException("应用分类无效，不能上传远程清单。");
  }
  boolean framework="android".equals(name)||"androidhwext".equals(name);
  if(framework&&(!"core".equals(category)||!scanned.optBoolean("system")||scanned.optBoolean("removable")))throw new IllegalArgumentException("系统框架条目必须保持保护状态。");
  String label=sanitizeText(optString(scanned,"label"),MAX_LABEL_LENGTH);
  JSONObject item=new JSONObject();
  item.put("packageName",name);
  item.put("label",label==null?name:label);
  item.put("category",category);
  item.put("removable",scanned.optBoolean("removable"));
  item.put("system",scanned.optBoolean("system"));
  String version=sanitizeText(optString(scanned,"versionName"),MAX_VERSION_LENGTH);
  if(version!=null)item.put("versionName",version);
  return item;
 }

 /** 整份扫描清单 → 远程清单；最多 1000 项，重复包名在上传前拒绝。 */
 public static JSONArray inventory(JSONArray scanned) throws JSONException {
  if(scanned==null)throw new IllegalArgumentException("应用清单缺失。");
  if(scanned.length()>1000)throw new IllegalArgumentException("应用清单超过中转服务上限（1000项）。");
  JSONArray out=new JSONArray();
  java.util.Set<String> seen=new java.util.HashSet<>();
  for(int i=0;i<scanned.length();i++){
   JSONObject item=inventoryItem(scanned.optJSONObject(i));
   if(!seen.add(item.optString("packageName")))throw new IllegalArgumentException("应用清单包含重复包名。");
   out.put(item);
  }
  return out;
 }

 /** 服务端 expiresAt：接受 ISO-8601 字符串或毫秒数值；无法解析返回 0（调用方按过期处理）。 */
 public static long parseExpiresAt(Object value){
  if(value instanceof Number){long ms=((Number)value).longValue();return ms>0?ms:0;}
  if(!(value instanceof String))return 0;
  String text=((String)value).trim();
  if(text.isEmpty())return 0;
  try{return Instant.parse(text).toEpochMilli();}catch(DateTimeParseException e){return 0;}
 }

 private static String optString(JSONObject object,String key){
  Object value=object.opt(key);
  return value instanceof String?(String)value:value==null?null:String.valueOf(value);
 }
}
