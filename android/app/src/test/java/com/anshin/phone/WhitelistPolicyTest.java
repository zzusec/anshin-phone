package com.anshin.phone;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class WhitelistPolicyTest {
 @Test public void requestedCommonAppsPreserved(){for(String p:Arrays.asList("com.tencent.mm","com.eg.android.AlipayGphone","com.baidu.searchbox","com.autonavi.minimap","com.ss.android.ugc.aweme","com.smile.gifmaker"))assertTrue(p,WhitelistPolicy.DEFAULTS.containsKey(p));}
 @Test public void userCanReplaceDefaultsAndAddCustom(){Set<String> custom=WhitelistPolicy.validate(Arrays.asList("com.tencent.mm","org.example.reader"));assertTrue(custom.contains("org.example.reader"));assertFalse(custom.contains("com.smile.gifmaker"));}
 @Test public void emptyUserListIsIntentionalNotResetToDefaults(){assertTrue(WhitelistPolicy.validate(Collections.emptyList()).isEmpty());}
 @Test public void malformedOrDuplicateListsRejected(){for(List<String> names:Arrays.asList(Arrays.asList("com.test.app","com.test.app"),Arrays.asList("com.test;rm"),Arrays.asList("../app")))try{WhitelistPolicy.validate(names);fail();}catch(IllegalArgumentException expected){}}
 @Test public void editableWhitelistDoesNotWeakenCorePolicy(){assertFalse(PackagePolicy.removable(PackagePolicy.category("com.android.phone",1000,true,"huawei",28,Collections.emptySet())));}
}
