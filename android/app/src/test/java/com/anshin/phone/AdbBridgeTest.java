package com.anshin.phone;

import android.os.IBinder;
import android.os.RemoteException;
import java.lang.reflect.Proxy;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdbBridgeTest {
 @After public void disconnect(){
  AuthorizedShell.close();AdbBridge.setListener(null);IAuthorizedCleanup current=AdbBridge.getService();
  if(current!=null)AdbBridge.invalidate(current,"test cleanup",null);
 }
 private static final class Helper implements IAuthorizedCleanup {
  boolean alive=true,failLink,failCall,denyCall;
  int version=AdbBridge.PROTOCOL_VERSION,links,uninstalls,restores,destroys;
  IBinder.DeathRecipient death;
  final IBinder binder=(IBinder)Proxy.newProxyInstance(IBinder.class.getClassLoader(),new Class<?>[]{IBinder.class},(proxy,method,args)->{
   switch(method.getName()){
    case "pingBinder":case "isBinderAlive":return alive;
    case "queryLocalInterface":return this;
    case "linkToDeath":if(failLink)throw new RemoteException("link failed");death=(IBinder.DeathRecipient)args[0];links++;return null;
    case "unlinkToDeath":boolean matched=death==args[0];if(matched)death=null;return matched;
    case "equals":return proxy==args[0];
    case "hashCode":return System.identityHashCode(proxy);
    case "toString":return "test helper";
    default:throw new AssertionError("Unexpected Binder operation: "+method.getName());
   }
  });
  @Override public IBinder asBinder(){return binder;}
  @Override public int protocolVersion(){return version;}
  @Override public String uninstall(String name)throws RemoteException{uninstalls++;if(failCall)throw new RemoteException("transport lost");if(denyCall)throw new SecurityException("protected");return "Success";}
  @Override public String restore(String name){restores++;return "installed";}
  @Override public String installBackup(String name,android.os.ParcelFileDescriptor[] files,String[] names,long[] sizes){return "Success";}
  @Override public void destroy(){destroys++;}
  void die(){alive=false;IBinder.DeathRecipient callback=death;if(callback!=null)callback.binderDied();}
 }
 @Test public void validRegistrationIsIdempotentAndNotifies()throws Exception{
  Helper helper=new Helper();int[] notifications={0};AdbBridge.setListener(()->notifications[0]++);
  AdbBridge.register(helper.binder);AdbBridge.register(helper.binder);
  assertTrue(AdbBridge.ready());assertSame(helper,AdbBridge.getService());assertEquals(1,helper.links);assertEquals(1,notifications[0]);
 }
 @Test public void rejectsWrongProtocolWithoutPublishingService(){
  Helper helper=new Helper();helper.version=1;
  assertThrows(SecurityException.class,()->AdbBridge.register(helper.binder));assertFalse(AdbBridge.ready());assertEquals(0,helper.links);
 }
 @Test public void rejectsUnavailableBinder(){
  Helper helper=new Helper();helper.alive=false;
  assertThrows(IllegalArgumentException.class,()->AdbBridge.register(helper.binder));
  assertThrows(IllegalArgumentException.class,()->AdbBridge.register(null));assertFalse(AdbBridge.ready());
 }
 @Test public void failedDeathLinkDoesNotPublish()throws Exception{
  Helper helper=new Helper();helper.failLink=true;
  assertThrows(RemoteException.class,()->AdbBridge.register(helper.binder));assertFalse(AdbBridge.ready());
 }
 @Test public void refusesSecondLiveHelper()throws Exception{
  Helper original=new Helper(),duplicate=new Helper();AdbBridge.register(original.binder);
  assertThrows(IllegalStateException.class,()->AdbBridge.register(duplicate.binder));assertSame(original,AdbBridge.getService());assertEquals(0,duplicate.links);
 }
 @Test public void deathClearsConnectionAndStaleDeathCannotClearReplacement()throws Exception{
  Helper old=new Helper();AdbBridge.register(old.binder);IBinder.DeathRecipient stale=old.death;
  old.die();assertFalse(AdbBridge.ready());assertNull(old.death);
  Helper replacement=new Helper();AdbBridge.register(replacement.binder);stale.binderDied();assertSame(replacement,AdbBridge.getService());
 }
 @Test public void detectsDeathEvenWithoutCallback()throws Exception{
  Helper helper=new Helper();AdbBridge.register(helper.binder);helper.alive=false;
  assertFalse(AdbBridge.ready());assertNull(helper.death);
 }
 @Test public void closingActivityKeepsAdbAuthorizationAndDoesNotDestroy()throws Exception{
  Helper helper=new Helper();AdbBridge.register(helper.binder);AuthorizedShell.close();
  assertTrue(AuthorizedShell.ready());assertEquals(0,helper.destroys);
  assertEquals("Success",AuthorizedShell.uninstall("org.example.app"));assertEquals("installed",AuthorizedShell.restore("com.huawei.himovie"));
  assertEquals(1,helper.uninstalls);assertEquals(1,helper.restores);
 }
 @Test public void binderFailureInvalidatesAndNeverRetries()throws Exception{
  Helper helper=new Helper();helper.failCall=true;AdbBridge.register(helper.binder);
  assertThrows(RemoteException.class,()->AuthorizedShell.uninstall("org.example.app"));
  assertFalse(AuthorizedShell.ready());assertEquals(1,helper.uninstalls);
  assertThrows(IllegalStateException.class,()->AuthorizedShell.uninstall("org.example.app"));assertEquals(1,helper.uninstalls);assertEquals(0,helper.restores);
 }
 @Test public void policyDenialIsNotAConnectionFailureOrPermissionFallback()throws Exception{
  Helper helper=new Helper();helper.denyCall=true;AdbBridge.register(helper.binder);
  assertThrows(SecurityException.class,()->AuthorizedShell.uninstall("com.android.phone"));
  assertTrue(AuthorizedShell.ready());assertEquals(1,helper.uninstalls);assertEquals(0,helper.restores);
 }
 @Test public void keepsExistingWireTransactionIds()throws Exception{
  for(String[] field:new String[][]{{"uninstall","1"},{"restore","2"},{"protocolVersion","3"},{"destroy","16777115"}}){
   java.lang.reflect.Field transaction=IAuthorizedCleanup.Stub.class.getDeclaredField("TRANSACTION_"+field[0]);transaction.setAccessible(true);
   assertEquals(Integer.parseInt(field[1]),transaction.getInt(null));
  }
 }
}
