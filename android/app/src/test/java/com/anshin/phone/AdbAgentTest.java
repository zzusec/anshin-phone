package com.anshin.phone;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdbAgentTest {
 @Test public void onlyShellMayStartHelper(){
  AdbAgent.requireShell(2000);
  for(int uid:new int[]{0,1000,10001,102000})assertThrows(SecurityException.class,()->AdbAgent.requireShell(uid));
 }
 @Test public void reRegistrationHasFiniteBudgetAndBackoff(){
  assertEquals(1000L,AdbAgent.retryDelayMillis(1));assertEquals(2000L,AdbAgent.retryDelayMillis(2));assertEquals(4000L,AdbAgent.retryDelayMillis(3));
  assertThrows(IllegalStateException.class,()->AdbAgent.retryDelayMillis(0));
  assertThrows(IllegalStateException.class,()->AdbAgent.retryDelayMillis(AdbAgent.MAX_REGISTRATION_ATTEMPTS));
  assertThrows(IllegalStateException.class,()->AdbAgent.retryDelayMillis(Integer.MAX_VALUE));
 }
}
