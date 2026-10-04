package com.anshin.phone;

import java.util.Collections;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class AuthorizedCleanupPolicyTest {
 private static void allowed(String name,int uid,boolean system,Set<String> defaults,boolean restore){
  AuthorizedCleanupService.requireAllowed(name,uid,system,"huawei",28,defaults,restore);
 }
 @Test public void validatesCallerAndExecutionUidIncludingRootRejection(){
  AuthorizedCleanupService.requireCaller(10042,10042,2000);
  for(int caller:new int[]{0,2000,10043})assertThrows(SecurityException.class,()->AuthorizedCleanupService.requireCaller(caller,10042,2000));
  assertThrows(SecurityException.class,()->AuthorizedCleanupService.requireCaller(10042,10042,0));
  assertThrows(SecurityException.class,()->AuthorizedCleanupService.requireCaller(110042,110042,2000));
 }
 @Test public void protectsEveryRuntimeDefaultEvenWhenThirdParty(){
  for(String name:new String[]{"org.example.home","org.example.ime","org.example.dialer","org.example.sms","org.example.settings","org.example.camera"}){
   allowed(name,10300,false,Collections.emptySet(),false);
   assertThrows(SecurityException.class,()->allowed(name,10300,false,Collections.singleton(name),false));
  }
 }
 @Test public void rechecksChangedRuntimePolicyOnEachInvocation(){
  String target="com.huawei.himovie";allowed(target,10300,true,Collections.emptySet(),false);
  assertThrows(SecurityException.class,()->allowed(target,10300,true,Collections.singleton(target),false));
  assertThrows(SecurityException.class,()->allowed(target,10300,true,Collections.singleton(target),true));
 }
 @Test public void coreOrUnknownSharedUidMemberFailsSameExecutionPolicy(){
  allowed("org.example.app",10300,false,Collections.emptySet(),false);
  for(String peer:new String[]{PackagePolicy.SELF,"com.android.phone","com.android.providers.contacts"})
   assertThrows(SecurityException.class,()->allowed(peer,10300,false,Collections.emptySet(),false));
  assertThrows(SecurityException.class,()->allowed("com.vendor.privileged",10300,true,Collections.emptySet(),false));
  for(int uid:new int[]{0,1000,2000,110300})assertThrows(SecurityException.class,()->allowed("org.example.app",uid,false,Collections.emptySet(),false));
 }
 @Test public void restoreIsOnlyForCurrentAdaptedOptionalPreinstall(){
  allowed("com.huawei.himovie",10300,true,Collections.emptySet(),true);
  assertThrows(SecurityException.class,()->allowed("org.example.app",10300,false,Collections.emptySet(),true));
  assertThrows(SecurityException.class,()->allowed("com.huawei.himovie",10300,false,Collections.emptySet(),true));
  assertThrows(SecurityException.class,()->AuthorizedCleanupService.requireAllowed("com.huawei.himovie",10300,true,"huawei",35,Collections.emptySet(),true));
  assertThrows(SecurityException.class,()->AuthorizedCleanupService.requireAllowed("com.huawei.himovie",10300,true,"other",28,Collections.emptySet(),true));
 }
 @Test public void commandArgumentsAreFixedNoKeepDataOrShellInterpretation(){
  assertArrayEquals(new String[]{"/system/bin/pm","uninstall","--user","0","org.example.app"},AuthorizedCleanupService.uninstallCommand("org.example.app"));
  assertArrayEquals(new String[]{"/system/bin/cmd","package","install-existing","--user","0","com.huawei.himovie"},AuthorizedCleanupService.restoreCommand("com.huawei.himovie"));
  for(String name:new String[]{"com.app;reboot","com.app\nrm","com.app -k","../app","",null}){
   assertThrows(SecurityException.class,()->AuthorizedCleanupService.uninstallCommand(name));assertThrows(SecurityException.class,()->AuthorizedCleanupService.restoreCommand(name));
  }
 }
 @Test public void latestWhitelistBlocksSelectedPackagesAndAllowsUnlistedPackages()throws Exception{
  AuthorizedCleanupService.requireNotWhitelisted("org.example.app","[\"com.tencent.mm\",\"com.eg.android.AlipayGphone\"]");
  assertThrows(SecurityException.class,()->AuthorizedCleanupService.requireNotWhitelisted("com.tencent.mm","[\"com.tencent.mm\"]"));
  AuthorizedCleanupService.requireNotWhitelisted("com.tencent.mm","[]");
  assertThrows(SecurityException.class,()->AuthorizedCleanupService.requireNotWhitelisted("com.tencent.mm","[\"com.tencent.mm\"]"));
 }
 @Test public void whitelistReadOrParseFailureIsFailClosed(){
  assertThrows(SecurityException.class,()->AuthorizedCleanupService.requireNotWhitelisted("org.example.app",null));
  for(String json:new String[]{"", "{}", "not-json", "["})assertThrows(org.json.JSONException.class,()->AuthorizedCleanupService.requireNotWhitelisted("org.example.app",json));
  for(String json:new String[]{"[42]","[null]","[\"bad;reboot\"]","[\"org.example.other\",{}]"})
   assertThrows(SecurityException.class,()->AuthorizedCleanupService.requireNotWhitelisted("org.example.app",json));
 }
}
