package com.anshin.phone;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class RemoteClient {
 public static String validateBase(String value)throws Exception{
  URI u=new URI(value.trim());String host=u.getHost();
  boolean local="localhost".equals(host)||"127.0.0.1".equals(host)||"[::1]".equals(host);
  if(host==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!("https".equals(u.getScheme())||local&&"http".equals(u.getScheme()))||!(u.getPath()==null||u.getPath().isEmpty()||"/".equals(u.getPath())))throw new IllegalArgumentException("服务地址必须是HTTPS根地址；仅本机调试允许HTTP。");
  return new URI(u.getScheme(),null,host,u.getPort(),null,null,null).toString();
 }
 public static JSONObject request(String base,String path,String method,String token,JSONObject body)throws Exception{
  String origin=validateBase(base);if(!path.startsWith("/api/"))throw new SecurityException("不允许的服务路径。");
  HttpURLConnection c=(HttpURLConnection)new URL(origin+path).openConnection();
  c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);c.setRequestProperty("Accept","application/json");
  if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);
  try{
   if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>1024*1024)throw new IOException("应用清单太大。");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
   int status=c.getResponseCode();if(status>=300&&status<400)throw new IOException("服务发生重定向，已停止发送凭证。请检查根地址。");
   InputStream input=status>=400?c.getErrorStream():c.getInputStream();if(input==null)throw new IOException("服务没有返回内容。");
   ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(InputStream in=input){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(buffer.size()+n>2*1024*1024)throw new IOException("服务响应超过安全限制。");buffer.write(b,0,n);}}
   JSONObject result=new JSONObject(buffer.toString("UTF-8"));
   if(status>=400){JSONObject error=result.optJSONObject("error");throw new IOException(error==null?"远程请求失败（"+status+"）。":error.optString("message","远程请求失败。"));}
   return result;
  }finally{c.disconnect();}
 }
}
