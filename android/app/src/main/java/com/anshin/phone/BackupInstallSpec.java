package com.anshin.phone;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java contract for the descriptor-only, user-0 APK restore channel. */
final class BackupInstallSpec {
 static final int MAX_FILES=128;
 static final long MAX_FILE_BYTES=512L*1024*1024,MAX_TOTAL_BYTES=2L*1024*1024*1024;
 static final String TEMP_PARENT="/data/local/tmp",TEMP_PREFIX="anshin-restore-";
 private static final Pattern SESSION=Pattern.compile("Success: created install session \\[([0-9]+)\\]");
 final String packageName;
 final String[] names;
 final long[] sizes;
 final long totalBytes;
 BackupInstallSpec(String packageName,Object[] files,String[] names,long[] sizes){
  if(!PackagePolicy.validName(packageName))throw new SecurityException("无效恢复包名。");
  if(files==null||names==null||sizes==null||files.length<1||files.length>MAX_FILES||files.length!=names.length||files.length!=sizes.length)
   throw new IllegalArgumentException("恢复文件数组为空、长度不匹配或数量超限。");
  this.packageName=packageName;this.names=names.clone();this.sizes=sizes.clone();
  Set<String> unique=new HashSet<>();int bases=0;long total=0;
  for(int i=0;i<files.length;i++){
   String name=this.names[i];long size=this.sizes[i];
   if(files[i]==null||name==null||!name.matches("(base|split-[1-9][0-9]{0,3})\\.apk")||!unique.add(name))
    throw new IllegalArgumentException("恢复描述符或APK文件名无效、重复。");
   if("base.apk".equals(name))bases++;
   if(size<=0||size>MAX_FILE_BYTES||(total+=size)>MAX_TOTAL_BYTES)throw new IllegalArgumentException("恢复APK大小超限或无效。");
  }
  if(bases!=1)throw new IllegalArgumentException("恢复必须有且仅有一个base.apk。");
  totalBytes=total;
 }
 void requirePackage(String actualName,String sharedUserId,boolean systemOrPrivileged,Set<String> runtimeCore){
  if(!packageName.equals(actualName))throw new SecurityException("恢复APK实际包名不匹配。");
  // No shared-UID installs: their siblings/privilege grant are not part of this backup contract.
  if(sharedUserId!=null||systemOrPrivileged||!"user".equals(PackagePolicy.category(actualName,10000,false,"",28,runtimeCore)))
   throw new SecurityException("拒绝恢复核心、自身、系统高权限或共享UID的APK；系统原包须用预装注册恢复。");
 }
 static void requireUid(int uid){
  if(uid<10000||uid>=100000)throw new SecurityException("拒绝高权限UID或非机主用户的软件包。");
 }
 void requireCopiedSize(int index,long copied)throws IOException{
  if(copied!=sizes[index])throw new IOException("恢复APK长度不匹配："+names[index]);
 }
 String[] createCommand(){return new String[]{"/system/bin/pm","install-create","--user","0","-r","-S",Long.toString(totalBytes)};}
 String[] writeCommand(int sessionId,int index,File directory){
  requireSession(sessionId);
  if(directory==null||!TEMP_PARENT.equals(directory.getParent())||!directory.getName().matches(TEMP_PREFIX+"[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
   throw new SecurityException("安装写入只允许shell自有临时目录。");
  return new String[]{"/system/bin/pm","install-write","-S",Long.toString(sizes[index]),Integer.toString(sessionId),names[index],new File(directory,names[index]).getAbsolutePath()};
 }
 static String[] commitCommand(int sessionId){requireSession(sessionId);return new String[]{"/system/bin/pm","install-commit",Integer.toString(sessionId)};}
 static String[] abandonCommand(int sessionId){requireSession(sessionId);return new String[]{"/system/bin/pm","install-abandon",Integer.toString(sessionId)};}
 private static void requireSession(int id){if(id<=0)throw new IllegalArgumentException("无效安装sessionId。");}
 static int parseSessionId(String output)throws IOException{
  Matcher match=SESSION.matcher(output==null?"":output.trim());
  if(match.matches())try{int id=Integer.parseInt(match.group(1));if(id>0)return id;}catch(NumberFormatException ignored){/* Report the original response below. */}
  throw new IOException("创建安装会话失败或响应不唯一："+output);
 }
 void requireWriteSuccess(int index,String output)throws IOException{
  if(output==null||!("Success: streamed "+sizes[index]+" bytes").equals(output.trim()))throw new IOException("写入APK失败："+output);
 }
 static void requireSuccess(String output)throws IOException{
  if(output==null||!"Success".equals(output.trim()))throw new IOException("安装会话操作失败："+output);
 }
}
