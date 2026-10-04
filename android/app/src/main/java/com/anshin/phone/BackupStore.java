package com.anshin.phone;
import android.content.Context;
import android.content.pm.PackageInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/** APK copies only, never user chat/account files. Fail before uninstall if backup fails. */
public final class BackupStore {
 private final Context context;
 public BackupStore(Context context){this.context=context;}

 public JSONObject backup(PackageInfo p) throws Exception {
  if(!PackagePolicy.validName(p.packageName))throw new SecurityException("无效包名");
  List<String> paths=new ArrayList<>();paths.add(p.applicationInfo.sourceDir);
  if(p.applicationInfo.splitSourceDirs!=null)java.util.Collections.addAll(paths,p.applicationInfo.splitSourceDirs);
  long bytes=0;for(String path:paths)bytes+=new File(path).length();
  if(context.getFilesDir().getUsableSpace()<bytes+50L*1024*1024)throw new IOException("手机剩余空间不足，未执行卸载。");
  boolean system=(p.applicationInfo.flags&android.content.pm.ApplicationInfo.FLAG_SYSTEM)!=0;
  return backupFiles(context.getFilesDir(),p.packageName,paths,p.getLongVersionCode(),system);
 }

 /**
  * 全部文件先写入独立临时目录并逐一校验，最后整体提交；任一步失败删除临时目录并保留上一份完整备份。
  */
 static JSONObject backupFiles(File filesRoot,String packageName,List<String> sources,long version,boolean system) throws Exception {
  if(!PackagePolicy.validName(packageName))throw new SecurityException("无效包名");
  File root=new File(filesRoot,"backups");
  if(!root.isDirectory()&&!root.mkdirs())throw new IOException("不能创建安装包备份目录。");
  File dir=new File(root,packageName);
  recoverInterrupted(root,packageName);
  File staging=new File(root,packageName+".staging-"+System.currentTimeMillis());
  if(!staging.isDirectory()&&!staging.mkdirs())throw new IOException("不能创建临时备份目录。");
  try{
   JSONArray files=new JSONArray();
   for(int i=0;i<sources.size();i++){
    String source=sources.get(i);
    File origin=new File(source);
    String name=i==0?"base.apk":"split-"+i+".apk";
    File dest=new File(staging,name);
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(InputStream in=new FileInputStream(origin);OutputStream out=new FileOutputStream(dest)){
     byte[] buf=new byte[65536];int count;while((count=in.read(buf))!=-1){out.write(buf,0,count);digest.update(buf,0,count);}
    }
    if(dest.length()!=origin.length())throw new IOException("安装包备份复制不完整，未卸载。");
    try(ZipFile zip=new ZipFile(dest)){if(zip.getEntry("AndroidManifest.xml")==null)throw new IOException("备份不是有效APK，未卸载。");}
    StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b));
    files.put(new JSONObject().put("name",name).put("sha256",hex.toString()).put("bytes",dest.length()));
   }
   JSONObject manifest=new JSONObject().put("packageName",packageName).put("version",version).put("system",system).put("files",files).put("time",System.currentTimeMillis());
   try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(staging,"manifest.json")),java.nio.charset.StandardCharsets.UTF_8)){writer.write(manifest.toString());}
   for(int i=0;i<files.length();i++){
    JSONObject f=files.getJSONObject(i);
    if(new File(staging,f.getString("name")).length()!=f.getLong("bytes"))throw new IOException("备份文件校验不一致，未卸载。");
   }
   commit(root,packageName,staging);
   return manifest;
  }catch(Exception e){
   deleteRecursive(staging);
   throw e;
  }
 }

 /** 原子提交：旧目录先挪到 .old，staging 就位后删除 .old；中途失败回滚 .old，保持上一份备份可用。 */
 private static void commit(File root,String packageName,File staging) throws IOException {
  File dir=new File(root,packageName);
  File old=new File(root,packageName+".old-"+System.currentTimeMillis());
  boolean hadOld=dir.isDirectory();
  if(hadOld&&!dir.renameTo(old))throw new IOException("不能替换旧备份目录。");
  if(!staging.renameTo(dir)){
   if(hadOld&&!old.renameTo(dir))throw new IOException("备份提交失败且回滚失败，请检查存储。");
   throw new IOException("安装包备份提交失败，已保留原备份。");
  }
  if(hadOld)deleteRecursive(old);
 }

 /** 进程在“旧目录已挪走、新目录未就位”之间中断时，恢复最近的 .old 备份。 */
 static void recoverInterrupted(File root,String packageName){
  File dir=new File(root,packageName);
  if(dir.isDirectory()&&new File(dir,"manifest.json").isFile())return;
  File[] candidates=root.listFiles((parent,name)->name.startsWith(packageName+".old-"));
  if(candidates==null)return;
  File newest=null;
  for(File candidate:candidates)if(new File(candidate,"manifest.json").isFile()&&(newest==null||candidate.lastModified()>newest.lastModified()))newest=candidate;
  if(newest==null)return;
  deleteRecursive(dir);
  newest.renameTo(dir);
 }

 /** Lightweight inventory for the UI; full hash and APK validation still occurs before installing. */
 public JSONObject metadata(String name)throws Exception{
  if(!PackagePolicy.validName(name))throw new SecurityException("无效包名。");
  File dir=new File(context.getFilesDir(),"backups/"+name);
  File manifest=new File(dir,"manifest.json");
  if(!manifest.isFile())throw new IOException("没有找到安装包备份。");
  JSONObject result=new JSONObject(new String(Files.readAllBytes(manifest.toPath()),java.nio.charset.StandardCharsets.UTF_8));
  if(!name.equals(result.getString("packageName")))throw new IOException("备份包名不匹配。");
  JSONArray files=result.getJSONArray("files");if(files.length()<1)throw new IOException("备份文件列表为空。");
  for(int i=0;i<files.length();i++){
   JSONObject item=files.getJSONObject(i);String file=item.getString("name");
   if(!file.matches("(base|split-[0-9]+)\\.apk")||!new File(dir,file).isFile())throw new IOException("备份安装包缺失。");
  }
  return result;
 }

 public JSONObject manifest(String name)throws Exception{
  if(!PackagePolicy.validName(name))throw new SecurityException("无效包名");
  return readManifest(context.getFilesDir(),name);
 }

 static JSONObject readManifest(File filesRoot,String name)throws Exception{
  if(!PackagePolicy.validName(name))throw new SecurityException("无效包名");
  File root=new File(filesRoot,"backups");
  recoverInterrupted(root,name);
  File file=new File(root,name+"/manifest.json");
  if(!file.isFile())throw new IOException("没有找到该应用的本地安装包备份。");
  JSONObject m=new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
  if(!name.equals(m.getString("packageName")))throw new SecurityException("备份包名不匹配。");
  JSONArray files=m.getJSONArray("files");
  for(int i=0;i<files.length();i++){
   JSONObject f=files.getJSONObject(i);String filename=f.getString("name");
   if(!filename.matches("(base|split-[0-9]+)\\.apk"))throw new SecurityException("备份文件名无效。");
   File apk=new File(file.getParentFile(),filename);MessageDigest digest=MessageDigest.getInstance("SHA-256");
   try(InputStream in=new FileInputStream(apk)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
   StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format("%02x",b));
   if(apk.length()!=f.getLong("bytes")||!hash.toString().equals(f.getString("sha256")))throw new IOException("安装包备份校验失败，停止恢复。");
  }
  return m;
 }

 public File apkFile(String name,String file){
  if(!file.matches("(base|split-[0-9]+)\\.apk"))throw new SecurityException("备份文件名无效。");
  return new File(context.getFilesDir(),"backups/"+name+"/"+file);
 }

 static void deleteRecursive(File file){
  if(file==null||!file.exists())return;
  File[] children=file.listFiles();
  if(children!=null)for(File child:children)deleteRecursive(child);
  file.delete();
 }
}
