package com.anshin.phone;
import org.junit.Test;
import static org.junit.Assert.*;
public class UpdateVersionTest {
 @Test public void compareSemanticVersionsNotLexical(){assertTrue(UpdateVersion.compare("v0.10.0","0.9.9")>0);assertEquals(0,UpdateVersion.compare("v0.4.0","0.4.0"));assertTrue(UpdateVersion.compare("0.3.9","0.4.0")<0);}
 @Test public void malformedReleaseAndDowngradeCannotBecomeAvailable(){for(String s:new String[]{"latest","v0.4.0-beta","../../app","1.0","0.4.0;rm"})assertThrows(IllegalArgumentException.class,()->UpdateVersion.compare(s,"0.4.0"));}
 @Test public void requireFullSha256(){String hash="a".repeat(64);assertEquals(hash,UpdateVersion.checksum(hash+"  app.apk\n"));assertThrows(IllegalArgumentException.class,()->UpdateVersion.checksum("abc app.apk"));assertThrows(IllegalArgumentException.class,()->UpdateVersion.checksum(hash+"\n"+hash));}
}
