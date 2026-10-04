package com.anshin.phone;
import android.content.Context;
import android.content.Intent;
import android.content.pm.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import androidx.core.content.FileProvider;

/** Anonymous public GitHub releases. All network calls are made by MainActivity's IO executor. */
public final class AppUpdater {
 private static final String REPO="zzusec/anshin-phone";
 private final Context context;
 public AppUpdater(Context c){context=c;}
 public JSONObject status(){try{return new JSONObject(context.getSharedPreferences("anshin",0).getString("updateStatus","{}"));}catch(JSONException e){throw new IllegalStateException("更新记录损坏。",e);}}
 public JSONObject check()throws Exception{
  JSONObject result=new JSONObject().put("currentVersion",BuildConfig.VERSION_NAME).put("checkedAt",System.currentTimeMillis()).put("repository","https://github.com/"+REPO);
  try{
   JSONObject release=new JSONObject(text("https://api.github.com/repos/"+REPO+"/releases/latest",1024*1024));
   if(release.optBoolean("draft")||release.optBoolean("prerelease"))throw new IOException("没有可用的稳定更新。");
   String tag=release.getString("tag_name");boolean available=UpdateVersion.compare(tag,BuildConfig.VERSION_NAME)>0;
   JSONArray assets=release.getJSONArray("assets");String apk=null,checksum=null;String expected="anshin-phone-"+tag+".apk";
   for(int i=0;i<assets.length();i++){JSONObject asset=assets.getJSONObject(i);if(expected.equals(asset.optString("name")))apk=asset.getString("browser_download_url");if((expected+".sha256").equals(asset.optString("name")))checksum=asset.getString("browser_download_url");}
   if(apk==null||checksum==null)throw new IOException("更新发布尚未包含完整安装包和校验文件。");
   requireAsset(apk);requireAsset(checksum);
   result.put("available",available).put("latestVersion",tag).put("apkUrl",apk).put("checksumUrl",checksum).put("message",available?"有新版本，可以在手机上更新。":"已经是最新版本。");
  }catch(Exception error){result.put("available",false).put("error","暂时无法检查更新，请稍后重试。").put("detail",error.getMessage());save(result);throw error;}
  save(result);return result;
 }
 public Intent downloadAndPrepare()throws Exception{
  JSONObject info=check();if(!info.optBoolean("available"))throw new IllegalStateException("已经是最新版本。");
  if(!context.getPackageManager().canRequestPackageInstalls())throw new IllegalStateException("安装更新尚未完成初始化，请连接电脑配置一次。");
  String expected=UpdateVersion.checksum(text(info.getString("checksumUrl"),4096));
  File dir=new File(context.getFilesDir(),"updates");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法保存更新安装包。");
  File temp=new File(dir,"download.tmp"),apk=new File(dir,"anshin-update.apk");
  MessageDigest hash=MessageDigest.getInstance("SHA-256");long bytes=0;
  try(InputStream in=open(info.getString("apkUrl"));OutputStream out=new FileOutputStream(temp)){
   byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){bytes+=n;if(bytes>100L*1024*1024)throw new IOException("更新安装包超过安全限制。");out.write(buffer,0,n);hash.update(buffer,0,n);}
  }catch(Exception e){temp.delete();throw e;}
  try{
   if(!expected.equals(hex(hash.digest())))throw new SecurityException("更新文件校验失败，已拒绝安装。");
   PackageManager pm=context.getPackageManager();PackageInfo archive=pm.getPackageArchiveInfo(temp.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES),current=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
   if(archive==null||!context.getPackageName().equals(archive.packageName)||archive.getLongVersionCode()<=current.getLongVersionCode())throw new SecurityException("更新软件身份或版本不正确。");
   if(current.signingInfo==null||archive.signingInfo==null)throw new SecurityException("更新签名无法验证，已停止安装。");
   Signature[] old=current.signingInfo.getApkContentsSigners(),next=archive.signingInfo.getApkContentsSigners();
   if(old.length!=next.length)throw new SecurityException("更新签名不匹配。");
   Set<String> signatures=new HashSet<>();for(Signature s:old)signatures.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
   for(Signature s:next)if(!signatures.contains(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))))throw new SecurityException("更新签名不匹配，已拒绝安装。");
   if(!temp.renameTo(apk))throw new IOException("更新文件提交失败。");
  }catch(Exception error){temp.delete();throw error;}
  Uri uri=FileProvider.getUriForFile(context,context.getPackageName()+".updates",apk);
  return new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
 }
 private void save(JSONObject result){context.getSharedPreferences("anshin",0).edit().putString("updateStatus",result.toString()).apply();}
 private static void requireAsset(String value)throws Exception{URI u=new URI(value);if(!"https".equals(u.getScheme())||!"github.com".equals(u.getHost())||!u.getPath().startsWith("/"+REPO+"/releases/download/")||u.getUserInfo()!=null)throw new SecurityException("更新下载地址不是本项目的正式发布。");}
 private static InputStream open(String value)throws Exception{
  URL url=new URL(value);
  for(int attempt=0;attempt<5;attempt++){
   String host=url.getHost();if(!"https".equals(url.getProtocol())||!(host.equals("github.com")||host.equals("api.github.com")||host.endsWith(".githubusercontent.com"))||url.getUserInfo()!=null)throw new SecurityException("更新请求跳转到不受信任地址。");
   HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","AnshinPhone/"+BuildConfig.VERSION_NAME);c.setRequestProperty("Accept",url.getHost().equals("api.github.com")?"application/vnd.github+json":"*/*");
   int status=c.getResponseCode();if(status>=300&&status<400){String target=c.getHeaderField("Location");c.disconnect();if(target==null)throw new IOException("更新跳转地址缺失。");url=new URL(url,target);continue;}
   if(status!=200){c.disconnect();throw new IOException("更新服务返回HTTP "+status);}
   return new FilterInputStream(c.getInputStream()){@Override public void close()throws IOException{try{super.close();}finally{c.disconnect();}}};
  }
  throw new IOException("更新跳转次数过多。");
 }
 private static String text(String url,int limit)throws Exception{try(InputStream in=open(url);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("更新响应过大。");out.write(b,0,n);}return out.toString("UTF-8");}}
 private static String hex(byte[] value){StringBuilder s=new StringBuilder();for(byte b:value)s.append(String.format(java.util.Locale.ROOT,"%02x",b));return s.toString();}
}
