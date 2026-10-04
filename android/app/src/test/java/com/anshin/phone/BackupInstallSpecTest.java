package com.anshin.phone;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class BackupInstallSpecTest {
 private static final String PACKAGE="org.example.app";
 private static final File DIRECTORY=new File("/data/local/tmp/anshin-restore-12345678-abcd-abcd-abcd-123456789abc");
 private static BackupInstallSpec spec(String[] names,long[] sizes){
  Object[] descriptors=new Object[names.length];Arrays.fill(descriptors,new Object());
  return new BackupInstallSpec(PACKAGE,descriptors,names,sizes);
 }
 private static BackupInstallSpec base(){return spec(new String[]{"base.apk"},new long[]{100});}
 @Test public void baseAndSplitUseFixedUserZeroArgvWithoutDowngrade(){
  BackupInstallSpec spec=spec(new String[]{"base.apk","split-1.apk"},new long[]{100,20});
  assertEquals(120,spec.totalBytes);
  assertArrayEquals(new String[]{"/system/bin/pm","install-create","--user","0","-r","-S","120"},spec.createCommand());
  assertArrayEquals(new String[]{"/system/bin/pm","install-write","-S","100","42","base.apk",DIRECTORY+"/base.apk"},spec.writeCommand(42,0,DIRECTORY));
  assertArrayEquals(new String[]{"/system/bin/pm","install-write","-S","20","42","split-1.apk",DIRECTORY+"/split-1.apk"},spec.writeCommand(42,1,DIRECTORY));
  assertArrayEquals(new String[]{"/system/bin/pm","install-commit","42"},BackupInstallSpec.commitCommand(42));
  assertArrayEquals(new String[]{"/system/bin/pm","install-abandon","42"},BackupInstallSpec.abandonCommand(42));
 }
 @Test public void baseDoesNotNeedToBeFirstAndInputArraysCannotChangeArgv(){
  String[] names={"split-2.apk","base.apk"};long[] sizes={20,100};BackupInstallSpec spec=spec(names,sizes);
  names[1]="../other.apk";sizes[1]=Long.MAX_VALUE;
  assertEquals("base.apk",spec.writeCommand(42,1,DIRECTORY)[5]);assertEquals("100",spec.writeCommand(42,1,DIRECTORY)[3]);
 }
 @Test public void rejectsInvalidPackageNames(){
  String longName="com."+String.join("",Collections.nCopies(221,"a"));
  for(String name:new String[]{null,"","android","../app","1org.example","org.example;reboot","org.example\napp","org.example --user 1",longName})
   assertThrows(SecurityException.class,()->new BackupInstallSpec(name,new Object[]{new Object()},new String[]{"base.apk"},new long[]{100}));
 }
 @Test public void rejectsNullMismatchedEmptyAndOversizedArrays(){
  assertThrows(IllegalArgumentException.class,()->new BackupInstallSpec(PACKAGE,null,new String[]{"base.apk"},new long[]{100}));
  assertThrows(IllegalArgumentException.class,()->new BackupInstallSpec(PACKAGE,new Object[]{new Object()},null,new long[]{100}));
  assertThrows(IllegalArgumentException.class,()->new BackupInstallSpec(PACKAGE,new Object[]{new Object()},new String[]{"base.apk"},null));
  assertThrows(IllegalArgumentException.class,()->new BackupInstallSpec(PACKAGE,new Object[0],new String[0],new long[0]));
  assertThrows(IllegalArgumentException.class,()->new BackupInstallSpec(PACKAGE,new Object[]{null},new String[]{"base.apk"},new long[]{100}));
  assertThrows(IllegalArgumentException.class,()->new BackupInstallSpec(PACKAGE,new Object[2],new String[]{"base.apk"},new long[]{100}));
  assertThrows(IllegalArgumentException.class,()->new BackupInstallSpec(PACKAGE,new Object[]{new Object()},new String[]{"base.apk"},new long[2]));
  int count=BackupInstallSpec.MAX_FILES+1;
  assertThrows(IllegalArgumentException.class,()->new BackupInstallSpec(PACKAGE,new Object[count],new String[count],new long[count]));
 }
 @Test public void rejectsPathsShellNamesBadSplitNamesAndMissingBase(){
  for(String name:new String[]{null,"../base.apk","/data/local/tmp/base.apk","base.apk;reboot","base.apk\n","base.APK","split-0.apk","split-01.apk","split-10000.apk","split-a.apk","manifest.json"})
   assertThrows(IllegalArgumentException.class,()->spec(new String[]{"base.apk",name},new long[]{100,20}));
  assertThrows(IllegalArgumentException.class,()->spec(new String[]{"split-1.apk"},new long[]{20}));
 }
 @Test public void rejectsDuplicateBaseOrSplits(){
  assertThrows(IllegalArgumentException.class,()->spec(new String[]{"base.apk","base.apk"},new long[]{100,100}));
  assertThrows(IllegalArgumentException.class,()->spec(new String[]{"base.apk","split-1.apk","split-1.apk"},new long[]{100,20,20}));
 }
 @Test public void checksIndividualAndTotalSizeBounds(){
  for(long size:new long[]{0,-1,BackupInstallSpec.MAX_FILE_BYTES+1,Long.MAX_VALUE})
   assertThrows(IllegalArgumentException.class,()->spec(new String[]{"base.apk"},new long[]{size}));
  long max=BackupInstallSpec.MAX_FILE_BYTES;
  assertEquals(BackupInstallSpec.MAX_TOTAL_BYTES,spec(new String[]{"base.apk","split-1.apk","split-2.apk","split-3.apk"},new long[]{max,max,max,max}).totalBytes);
  assertThrows(IllegalArgumentException.class,()->spec(new String[]{"base.apk","split-1.apk","split-2.apk","split-3.apk","split-4.apk"},new long[]{max,max,max,max,1}));
 }
 @Test public void rejectsTruncatedOrLargerDescriptorContent()throws Exception{
  base().requireCopiedSize(0,100);
  for(long size:new long[]{0,99,101,Long.MAX_VALUE})assertThrows(IOException.class,()->base().requireCopiedSize(0,size));
 }
 @Test public void acceptsOnlyOneStrictSessionResponse()throws Exception{
  assertEquals(42,BackupInstallSpec.parseSessionId("Success: created install session [42]\n"));
  assertEquals(Integer.MAX_VALUE,BackupInstallSpec.parseSessionId("Success: created install session [2147483647]"));
  for(String output:new String[]{null,"","Success","Failure [invalid]","Success: created install session [0]","Success: created install session [-1]","Success: created install session [2147483648]","Success: created install session [42]\nSuccess: created install session [43]","Failure [bad]\nSuccess: created install session [42]","prefix Success: created install session [42]"})
   assertThrows(IOException.class,()->BackupInstallSpec.parseSessionId(output));
 }
 @Test public void neverTreatsFailureOrWrongLengthAsWriteSuccess()throws Exception{
  base().requireWriteSuccess(0,"Success: streamed 100 bytes\n");
  for(String output:new String[]{null,"Success","Success: streamed 99 bytes","Failure [write failed]","Success: streamed 100 bytes\nFailure [bad]"})
   assertThrows(IOException.class,()->base().requireWriteSuccess(0,output));
 }
 @Test public void commitAndAbandonNeedExactSuccess()throws Exception{
  BackupInstallSpec.requireSuccess("Success\n");
  for(String output:new String[]{null,"","Failure [INSTALL_FAILED_VERSION_DOWNGRADE]","Not Success","Success: pending","Success\nFailure [bad]"})
   assertThrows(IOException.class,()->BackupInstallSpec.requireSuccess(output));
 }
 @Test public void argvRejectsOtherDirectoriesAndInvalidSessions(){
  for(File dir:new File[]{null,new File("/sdcard/restore"),new File("/data/local/tmp/user-backup"),new File("/data/local/tmp/anshin-restore-../other"),new File("data/local/tmp/anshin-restore-12345678-abcd-abcd-abcd-123456789abc")})
   assertThrows(SecurityException.class,()->base().writeCommand(42,0,dir));
  for(int id:new int[]{0,-1,Integer.MIN_VALUE}){
   assertThrows(IllegalArgumentException.class,()->base().writeCommand(id,0,DIRECTORY));
   assertThrows(IllegalArgumentException.class,()->BackupInstallSpec.commitCommand(id));
   assertThrows(IllegalArgumentException.class,()->BackupInstallSpec.abandonCommand(id));
  }
 }
 @Test public void rejectsCoreSelfRuntimeDefaultsAndProviders(){
  for(String name:new String[]{PackagePolicy.SELF,"com.android.phone","com.android.settings","com.android.providers.downloads","com.huawei.systemmanager"}){
   BackupInstallSpec spec=new BackupInstallSpec(name,new Object[]{new Object()},new String[]{"base.apk"},new long[]{100});
   assertThrows(SecurityException.class,()->spec.requirePackage(name,null,false,Collections.emptySet()));
  }
  assertThrows(SecurityException.class,()->base().requirePackage(PACKAGE,null,false,Collections.singleton(PACKAGE)));
 }
 @Test public void rejectsWrongPackageSystemFlagsAndEverySharedUid(){
  assertThrows(SecurityException.class,()->base().requirePackage("org.example.other",null,false,Collections.emptySet()));
  assertThrows(SecurityException.class,()->base().requirePackage(PACKAGE,null,true,Collections.emptySet()));
  for(String shared:new String[]{"android.uid.system","android.uid.phone","org.example.shared",""})
   assertThrows(SecurityException.class,()->base().requirePackage(PACKAGE,shared,false,Collections.emptySet()));
 }
 @Test public void rejectsPrivilegedAndOtherUserUids(){
  BackupInstallSpec.requireUid(10000);BackupInstallSpec.requireUid(99999);
  for(int uid:new int[]{-1,0,1000,2000,9999,100000,110000})assertThrows(SecurityException.class,()->BackupInstallSpec.requireUid(uid));
 }
 @Test public void allowsNonCoreWhitelistSoftwareToBeRestored(){
  String name="com.tencent.mm";
  BackupInstallSpec spec=new BackupInstallSpec(name,new Object[]{new Object()},new String[]{"base.apk"},new long[]{100});
  spec.requirePackage(name,null,false,Collections.emptySet());
 }
}
