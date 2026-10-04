package com.anshin.phone;

import java.util.*;

/** User-editable app preservation, separate from non-editable system/core protection. */
public final class WhitelistPolicy {
    public static final Map<String,String> DEFAULTS;
    static {
        Map<String,String> m=new LinkedHashMap<>();
        m.put("com.tencent.mm","微信");
        m.put("com.eg.android.AlipayGphone","支付宝");
        m.put("com.baidu.searchbox","百度");
        m.put("com.baidu.searchbox.lite","百度极速版");
        m.put("com.baidu.BaiduMap","百度地图");
        m.put("com.autonavi.minimap","高德地图");
        m.put("com.ss.android.ugc.aweme","抖音");
        m.put("com.ss.android.ugc.aweme.lite","抖音极速版");
        m.put("com.smile.gifmaker","快手");
        m.put("com.kuaishou.nebula","快手极速版");
        m.put("com.tencent.mobileqq","QQ");
        m.put("com.taobao.taobao","淘宝");
        m.put("com.jingdong.app.mall","京东");
        m.put("com.xunmeng.pinduoduo","拼多多");
        m.put("com.tencent.wetype","微信输入法");
        m.put("com.whatsapp","WhatsApp");
        m.put("org.telegram.messenger","Telegram");
        m.put("org.telegram.messenger.web","Telegram");
        m.put("org.mozilla.firefox","Firefox");
        m.put("com.android.chrome","Chrome");
        // Existing personal connection/file-sharing tools remain usable after optimization.
        m.put("com.follow.clash","Clash");
        m.put("com.nebula.karing","Karing");
        m.put("app.landrop.landrop_flutter","LANDrop");
        DEFAULTS=Collections.unmodifiableMap(m);
    }
    public static Set<String> validate(Collection<String> packages) {
        if(packages==null||packages.size()>1000)throw new IllegalArgumentException("常用软件名单无效或过大。");
        Set<String> result=new LinkedHashSet<>();
        for(String name:packages){
            if(!PackagePolicy.validName(name))throw new IllegalArgumentException("常用软件名单含无效包名。");
            if(!result.add(name))throw new IllegalArgumentException("常用软件名单有重复项目。");
        }
        return Collections.unmodifiableSet(result);
    }
    private WhitelistPolicy(){}
}
