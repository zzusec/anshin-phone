package com.anshin.phone;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;
public class PackagePolicyTest {
 @Test public void protectsCoreEvenWhenMetadataCallsItThirdParty(){assertEquals("core",PackagePolicy.category("com.android.phone",10100,false,"huawei",28,Set.of()));assertEquals("core",PackagePolicy.category(PackagePolicy.SELF,10100,false,"huawei",28,Set.of()));}
 @Test public void protectsCurrentLauncherAndKeyboard(){assertEquals("core",PackagePolicy.category("org.example.keyboard",10300,false,"huawei",28,Set.of("org.example.keyboard")));}
 @Test public void doesNotAssumeSystemEqualsCoreOrMalware(){assertEquals("unknown",PackagePolicy.category("com.vendor.business",10100,true,"huawei",28,Set.of()));assertFalse(PackagePolicy.removable("unknown"));}
 @Test public void limitsPresetToTestedHuaweiAndroid9(){assertEquals("optional",PackagePolicy.category("com.huawei.himovie",10100,true,"huawei",28,Set.of()));assertEquals("unknown",PackagePolicy.category("com.huawei.himovie",10100,true,"huawei",35,Set.of()));}
 @Test public void refusesSharedSystemUidAndInjection(){assertEquals("core",PackagePolicy.category("com.vendor.optional",1000,true,"huawei",28,Set.of()));for(String name:new String[]{"com.app;reboot","com.app\nrm", "../app","com.app -k",""})assertFalse(PackagePolicy.validName(name));}
 @Test public void allowsNonCoreThirdPartyWithoutAutomaticallyKeepingAllPersonalApps(){assertEquals("user",PackagePolicy.category("com.tencent.mm",10300,false,"huawei",28,Set.of()));}
}
