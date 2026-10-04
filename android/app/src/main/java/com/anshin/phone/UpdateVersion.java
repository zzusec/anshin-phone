package com.anshin.phone;
/** Strict release tags, not arbitrary executable filenames or lexical version comparison. */
public final class UpdateVersion {
 public static int compare(String next,String current){
  int[] a=parse(next),b=parse(current);for(int i=0;i<3;i++)if(a[i]!=b[i])return Integer.compare(a[i],b[i]);return 0;
 }
 private static int[] parse(String v){
  if(v==null||!v.matches("v?[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}"))throw new IllegalArgumentException("更新版本格式无效。");
  String[] parts=v.replaceFirst("^v","").split("\\.");return new int[]{Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2])};
 }
 public static String checksum(String value){if(value==null||!value.trim().matches("[A-Fa-f0-9]{64}(?:[ \t]+[^\\r\\n]+)?"))throw new IllegalArgumentException("更新校验文件格式无效。");return value.trim().substring(0,64).toLowerCase(java.util.Locale.ROOT);}
 private UpdateVersion(){}
}
