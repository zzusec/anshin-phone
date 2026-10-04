package com.anshin.phone;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdbSetupProviderTest {
 @Test public void registerAndStatusAreShellOnlyEvenForOwnAppOrRoot(){
  AdbSetupProvider.requireShell(2000);
  for(int uid:new int[]{0,1000,10042,102000,-1})assertThrows(SecurityException.class,()->AdbSetupProvider.requireShell(uid));
  assertThrows(SecurityException.class,()->new AdbSetupProvider().call("status",null,null));
 }
 @Test public void registrationMustTargetThisOwnerUserApp(){
  AdbSetupProvider.requireTarget(2000,10042,10042);
  assertThrows(SecurityException.class,()->AdbSetupProvider.requireTarget(0,10042,10042));
  assertThrows(SecurityException.class,()->AdbSetupProvider.requireTarget(2000,10043,10042));
  assertThrows(SecurityException.class,()->AdbSetupProvider.requireTarget(2000,110042,110042));
  assertThrows(SecurityException.class,()->AdbSetupProvider.requireTarget(2000,2000,2000));
  assertThrows(SecurityException.class,()->AdbSetupProvider.requireTarget(2000,-1,10042));
 }
 @Test public void crudIsNotAnAlternativeSetupOrDataChannel(){
  AdbSetupProvider provider=new AdbSetupProvider();
  assertThrows(UnsupportedOperationException.class,()->provider.query(null,null,null,null,null));
  assertThrows(UnsupportedOperationException.class,()->provider.insert(null,null));
  assertThrows(UnsupportedOperationException.class,()->provider.delete(null,null,null));
  assertThrows(UnsupportedOperationException.class,()->provider.update(null,null,null,null));
  assertThrows(UnsupportedOperationException.class,()->provider.getType(null));
 }
}
