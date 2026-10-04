package com.anshin.phone;

import java.util.Set;
import java.util.regex.Pattern;

/** A system flag is not a core/safety verdict. Unknown system packages are not auto-deleted. */
public final class PackagePolicy {
    public static final String SELF = "com.anshin.phone";
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Set<String> CORE = set(
        SELF,"android","com.android.phone","com.android.contacts","com.android.settings","com.android.systemui",
        "com.android.packageinstaller","com.google.android.packageinstaller","com.android.permissioncontroller",
        "com.google.android.permissioncontroller","com.android.bluetooth","com.android.webview","com.google.android.webview",
        "com.android.networkstack","com.google.android.networkstack","com.android.captiveportallogin",
        "com.android.location.fused","com.android.vpndialogs","com.android.shell","com.android.nfc",
        "com.google.android.gms","com.google.android.gsf","com.google.android.gsf.login","com.android.vending",
        "com.huawei.android.launcher","com.huawei.camera","com.huawei.hidisk","com.huawei.hwid",
        "com.huawei.android.findmyphone","com.huawei.systemmanager","com.huawei.powergenie","com.huawei.iaware",
        "com.huawei.webview","com.huawei.ims","com.huawei.lbs","com.huawei.android.hwouc",
        "com.huawei.android.hsf","com.huawei.systemserver","com.android.mms"
    );
    private static final Set<String> HUAWEI_9_OPTIONAL = set(
        "com.huawei.appmarket","com.huawei.himovie","com.huawei.vassistant","com.huawei.android.thememanager",
        "com.huawei.android.FMRadio","com.huawei.printservice","com.huawei.android.UEInfoCheck",
        "com.huawei.android.AutoRegSms","com.huawei.hiviewtunnel"
    );
    public static boolean validName(String name) { return name != null && NAME.matcher(name).matches() && name.length() <= 220; }
    public static boolean validInventoryName(String name) { return validName(name) || "android".equals(name) || "androidhwext".equals(name); }
    public static String category(String name, int uid, boolean system, String manufacturer, int sdk, Set<String> runtimeCore) {
        if (!validName(name) || uid < 10000 || CORE.contains(name) || name.startsWith("com.android.providers.") || runtimeCore.contains(name)) return "core";
        if (!system) return "user";
        if ("huawei".equalsIgnoreCase(manufacturer) && sdk == 28 && HUAWEI_9_OPTIONAL.contains(name)) return "optional";
        return "unknown";
    }
    public static boolean removable(String category) { return "user".equals(category) || "optional".equals(category); }
    private static Set<String> set(String... values) { return java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(values))); }
    private PackagePolicy() {}
}
