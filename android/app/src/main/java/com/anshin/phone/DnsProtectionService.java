package com.anshin.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Integration: the Activity obtains VpnService.prepare consent before ACTION_START.
 * The manifest owner must declare BIND_VPN_SERVICE, INTERNET, ACCESS_NETWORK_STATE,
 * FOREGROUND_SERVICE and FOREGROUND_SERVICE_SPECIAL_USE, and the specialUse subtype.
 * There is deliberately no boot receiver, automatic restart, default route or allowBypass.
 */
public final class DnsProtectionService extends VpnService {
    public static final String ACTION_START = "com.anshin.phone.DNS_START";
    public static final String ACTION_STOP = "com.anshin.phone.DNS_STOP";
    public static final String CHANNEL_ID = "dns-protection";
    public static final int NOTIFICATION_ID = 42;
    public static final String LIMITATIONS = "仅过滤使用虚拟DNS 10.77.0.2的IPv4 UDP/53 A/AAAA查询；"
            + "有限本地域名名单，非全面广告过滤。不拦截TCP DNS、IPv6 DNS、自定义DNS、DoH/DoT，"
            + "不解密HTTPS，不过滤同域广告或硬编码IP。IPv6及非DNS业务走原网络。"
            + "未启用allowBypass；路由外流量及平台特权流量不保证过滤。"
            + "无IPv4系统DNS时返回SERVFAIL；底层网络或DNS配置变化时停止并报错，需手动重启。";

    private static final Object STATE_LOCK = new Object();
    private static DnsProtectionService owner;

    private SharedPreferences preferences;
    private ConnectivityManager connectivity;
    private ParcelFileDescriptor tunnel;
    private DatagramSocket socket;
    private Thread worker;
    private ConnectivityManager.NetworkCallback physicalCallback;
    private ConnectivityManager.NetworkCallback defaultCallback;
    private boolean physicalRegistered;
    private boolean defaultRegistered;
    private boolean running;
    private boolean starting;
    private volatile DomainRules currentRules;
    private long generation;
    private int latestStartId;
    private long blockedCount;
    private String lastError = "";
    private String upstream = "";

    /** Never trust a persisted active flag after process death. Does not log query history. */
    public static JSONObject status(Context context) {
        synchronized (STATE_LOCK) {
            SharedPreferences prefs = context.getSharedPreferences("anshin", MODE_PRIVATE);
            boolean active = owner != null && owner.running && owner.tunnel != null;
            if (!active && prefs.getBoolean("vpnActive", false)) {
                prefs.edit().putBoolean("vpnActive", false).apply();
            }
            JSONObject result = new JSONObject();
            try {
                result.put("active", active);
                result.put("starting", owner != null && owner.starting);
                result.put("blockedCount", active ? owner.blockedCount : readCount(prefs));
                result.put("lastError", active ? owner.lastError : prefs.getString("vpnError", ""));
                result.put("upstream", active ? owner.upstream : prefs.getString("upstream", ""));
                result.put("mode", "dns-only");
                result.put("limitations", LIMITATIONS);
            } catch (JSONException e) {
                throw new IllegalStateException("Cannot construct DNS status", e);
            }
            return result;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (STATE_LOCK) {
            preferences = getSharedPreferences("anshin", MODE_PRIVATE);
            connectivity = getSystemService(ConnectivityManager.class);
            blockedCount = readCount(preferences);
            lastError = preferences.getString("vpnError", "");
            upstream = preferences.getString("upstream", "");
            if (owner == null) {
                preferences.edit().putBoolean("vpnActive", false).apply();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (STATE_LOCK) {
            latestStartId = startId;
        }
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession(null);
            stopSelf(startId);
        } else if (ACTION_START.equals(action)) {
            queueSessionStart(startId);
        } else {
            synchronized (STATE_LOCK) {
                if (!running) {
                    stopSelf(startId);
                }
            }
        }
        return START_NOT_STICKY;
    }

    /** Service callbacks run on the main thread: only notification/dispatch belong here. */
    private void queueSessionStart(int startId) {
        synchronized (STATE_LOCK) {
            if (running || starting) return;
            final long token = ++generation;
            try {
                showForegroundNotification();
                starting = true;
                owner = this;
                lastError = "";
                persistLocked();
                worker = new Thread(() -> startSession(startId, token), "anshin-dns-only");
                worker.start();
            } catch (Exception error) {
                closeLocked("广告防护未能启动：" + describe(error));
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
        }
    }

    /** Socket bind/connect and tunnel setup must never execute on the service main thread. */
    private void startSession(int startId, long token) {
        final DomainRules rules;
        final ParcelFileDescriptor sessionTunnel;
        final DatagramSocket sessionSocket;
        synchronized (STATE_LOCK) {
            if (token != generation || !starting) return;
            try {
                if (connectivity == null) throw new IOException("无法监测底层网络");
                rejectOtherVpn();
                if (VpnService.prepare(this) != null) throw new IOException("初始化未完成，请连接电脑完成一次配置");
                rules = RuleListStore.load(this,readRules("blockedDomains"),readRules("allowedDomains"));
                currentRules = rules;
                Network candidate = connectivity.getActiveNetwork();
                final Network network = usable(candidate) ? candidate : null;
                final LinkProperties initial = network == null ? null : connectivity.getLinkProperties(network);
                Inet4Address dns = systemDns(initial);
                if (network == null || dns == null) throw new IOException("当前网络没有可用的DNS，请连接网络后重试");
                upstream = dns.getHostAddress();
                lastError = "";
                socket = new DatagramSocket(null);
                if (!protect(socket)) throw new IOException("无法保护上游DNS连接");
                network.bindSocket(socket);
                socket.connect(new InetSocketAddress(dns, 53));
                socket.setSoTimeout(3000);
                monitorNetworks(network, initial, token);
                rejectOtherVpn();
                if (!network.equals(connectivity.getActiveNetwork()) || !usable(network)
                        || !java.util.Objects.equals(initial, connectivity.getLinkProperties(network))) {
                    throw new IOException("网络正在切换，请稍后重试");
                }
                Builder builder = new Builder().setSession("安心手机广告防护")
                        .addAddress("10.77.0.1", 32).addDnsServer("10.77.0.2")
                        .addRoute("10.77.0.2", 32).allowFamily(OsConstants.AF_INET6)
                        .setMtu(1500).setBlocking(false).setUnderlyingNetworks(new Network[]{network});
                tunnel = builder.establish();
                if (tunnel == null) throw new IOException("建立防护失败或授权已撤回");
                sessionTunnel = tunnel;
                sessionSocket = socket;
                starting = false;
                running = true;
                persistLocked();
            } catch (Exception error) {
                closeLocked("广告防护启动失败：" + describe(error));
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
                return;
            }
        }
        // Never hold STATE_LOCK for the packet loop; STOP and callbacks must remain responsive.
        runSession(token, sessionTunnel, sessionSocket, rules);
    }

    /** Rule refresh updates an immutable snapshot off the main thread, without restarting VPN. */
    public static void reloadRulesIfRunning(Context context) {
        final DnsProtectionService target; final long token;
        synchronized (STATE_LOCK) { target=owner; if(target==null||!target.running)return; token=target.generation; }
        Thread refresh=new Thread(()->{
            try {
                DomainRules next=RuleListStore.load(context,target.readRules("blockedDomains"),target.readRules("allowedDomains"));
                synchronized (STATE_LOCK) { if(owner==target&&target.running&&target.generation==token)target.currentRules=next; }
            } catch(Exception failure) {
                synchronized(STATE_LOCK){if(owner==target&&target.running&&target.generation==token){target.lastError="规则刷新未完成，继续使用上一份规则。";target.persistLocked();}}
            }
        },"anshin-rules-reload");
        refresh.start();
    }

    private void rejectOtherVpn() throws IOException {
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                throw new IOException("检测到其他VPN，拒绝启动以免替换它");
            }
        }
    }

    private boolean usable(Network network) {
        NetworkCapabilities caps = network == null ? null : connectivity.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private static Inet4Address systemDns(LinkProperties properties) {
        if (properties != null) {
            for (InetAddress address : properties.getDnsServers()) {
                if (address instanceof Inet4Address && !address.isAnyLocalAddress()
                        && !address.isLoopbackAddress() && !address.isMulticastAddress()
                        && !"10.77.0.2".equals(address.getHostAddress())) {
                    return (Inet4Address) address;
                }
            }
        }
        return null; // Never silently substitute a public resolver.
    }

    private void monitorNetworks(Network selected, LinkProperties initial, long token) {
        Set<Network> existing = new HashSet<>(Arrays.asList(connectivity.getAllNetworks()));
        physicalCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (!existing.contains(network)) {
                    terminate(token, "底层网络新增或切换，DNS保护已停止，请手动重启");
                }
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(selected)) {
                    terminate(token, "底层网络已断开，DNS保护已停止");
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (network.equals(selected) && (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))) {
                    terminate(token, "底层网络能力变化，DNS保护已停止");
                }
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                if (network.equals(selected) && !properties.equals(initial)) {
                    terminate(token, "底层网络或系统DNS配置变化，DNS保护已停止，请手动重启");
                }
            }
        };
        connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), physicalCallback);
        physicalRegistered = true;
        defaultCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                // Establishing our VPN changes the default to VPN; that is not a physical switch.
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        && !network.equals(selected)) {
                    terminate(token, "默认底层网络切换，DNS保护已停止，请手动重启");
                }
            }
        };
        connectivity.registerDefaultNetworkCallback(defaultCallback);
        defaultRegistered = true;
    }

    private void runSession(long token, ParcelFileDescriptor descriptor,
                            DatagramSocket upstreamSocket, DomainRules rules) {
        String failure = null;
        byte[] buffer = new byte[65535];
        StructPollfd poll = new StructPollfd();
        poll.events = (short) OsConstants.POLLIN;
        try {
            poll.fd = descriptor.getFileDescriptor();
            while (isCurrent(token)) {
                poll.revents = 0;
                if (Os.poll(new StructPollfd[]{poll}, 250) == 0) {
                    continue;
                }
                int length;
                synchronized (STATE_LOCK) {
                    if (!currentLocked(token)) {
                        break;
                    }
                    if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) {
                        throw new IOException("TUN不可用");
                    }
                    try {
                        length = Os.read(poll.fd, buffer, 0, buffer.length);
                    } catch (ErrnoException e) {
                        if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) {
                            continue;
                        }
                        throw e;
                    }
                }
                if (length <= 0) {
                    throw new IOException("TUN已关闭");
                }
                final DnsPacket query;
                try {
                    query = DnsPacket.parse(buffer, length);
                    if (!query.isDestination(10, 77, 0, 2)) {
                        throw new IllegalArgumentException("非虚拟DNS目标");
                    }
                } catch (IllegalArgumentException e) {
                    recordError(token, "已拒绝不支持或畸形报文：" + e.getMessage());
                    continue;
                }
                boolean blocked = (currentRules==null?rules:currentRules).isBlocked(query.getDomain());
                byte[] response;
                if (blocked) {
                    response = query.errorResponse(DnsPacket.NXDOMAIN);
                } else {
                    try {
                        if (upstreamSocket == null) {
                            throw new IOException("无系统IPv4 DNS上游");
                        }
                        byte[] request = query.getQuery();
                        upstreamSocket.send(new DatagramPacket(request, request.length));
                        byte[] answer = new byte[DnsPacket.MAX_DNS_LENGTH + 1];
                        DatagramPacket received = new DatagramPacket(answer, answer.length);
                        upstreamSocket.receive(received);
                        response = query.forwardedResponse(answer, received.getLength());
                    } catch (IOException | IllegalArgumentException e) {
                        recordError(token, "DNS转发失败，返回SERVFAIL：" + describe(e));
                        response = query.errorResponse(DnsPacket.SERVFAIL);
                    }
                }
                synchronized (STATE_LOCK) {
                    if (!currentLocked(token)) {
                        break;
                    }
                    int written = Os.write(poll.fd, response, 0, response.length);
                    if (written != response.length) {
                        throw new IOException("TUN响应写入不完整");
                    }
                    if (blocked) {
                        if (blockedCount != Long.MAX_VALUE) {
                            blockedCount++;
                        }
                        persistLocked();
                    }
                }
            }
        } catch (Exception e) {
            failure = "DNS工作线程停止：" + describe(e);
        } finally {
            terminate(token, failure == null ? "DNS工作线程已停止" : failure);
        }
    }

    private boolean isCurrent(long token) {
        synchronized (STATE_LOCK) {
            return currentLocked(token);
        }
    }

    private boolean currentLocked(long token) {
        return running && generation == token && owner == this;
    }

    private void recordError(long token, String error) {
        synchronized (STATE_LOCK) {
            if (currentLocked(token)) {
                lastError = error;
                persistLocked();
            }
        }
    }

    private void terminate(long token, String error) {
        Thread exiting;
        synchronized (STATE_LOCK) {
            if (!currentLocked(token)) {
                return;
            }
            exiting = closeLocked(error);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(latestStartId);
        }
        awaitExit(exiting);
    }

    private void stopSession(String error) {
        Thread exiting;
        synchronized (STATE_LOCK) {
            exiting = closeLocked(error);
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        awaitExit(exiting);
    }

    /** All worker writes are generation-guarded, including queued network callbacks after STOP. */
    private Thread closeLocked(String error) {
        ++generation;
        running = false;
        starting = false;
        currentRules = null;
        if (owner == this) {
            owner = null;
        }
        if (error != null) {
            lastError = error;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (tunnel != null) {
            try {
                tunnel.close();
            } catch (IOException e) {
                lastError += "; 关闭TUN失败：" + describe(e);
            }
            tunnel = null;
        }
        if (physicalRegistered) {
            try {
                connectivity.unregisterNetworkCallback(physicalCallback);
            } catch (RuntimeException e) {
                lastError += "; 注销网络监测失败：" + describe(e);
            }
            physicalRegistered = false;
        }
        if (defaultRegistered) {
            try {
                connectivity.unregisterNetworkCallback(defaultCallback);
            } catch (RuntimeException e) {
                lastError += "; 注销默认网络监测失败：" + describe(e);
            }
            defaultRegistered = false;
        }
        Thread exiting = worker;
        worker = null;
        if (exiting != null) {
            exiting.interrupt();
        }
        persistLocked();
        return exiting;
    }

    private static void awaitExit(Thread exiting) {
        if (exiting != null && exiting != Thread.currentThread()) {
            try {
                // poll is bounded to 250ms; socket.close wakes any DNS receive immediately.
                exiting.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void persistLocked() {
        preferences.edit().putBoolean("vpnActive", running).putLong("blockedCount", blockedCount)
                .putString("vpnError", lastError).putString("upstream", upstream).apply();
    }

    private List<String> readRules(String key) throws JSONException {
        JSONArray values = new JSONArray(preferences.getString(key, "[]"));
        List<String> domains = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            Object value = values.get(i);
            if (!(value instanceof String)) {
                throw new JSONException("Domain rule must be a string: " + key);
            }
            domains.add((String) value);
        }
        return domains;
    }

    private static long readCount(SharedPreferences prefs) {
        Object value = prefs.getAll().get("blockedCount");
        return value instanceof Number ? Math.max(0, ((Number) value).longValue()) : 0;
    }

    private static String describe(Exception error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    private void showForegroundNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            throw new IllegalStateException("通知服务不可用");
        }
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                "DNS-only广告域名过滤", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, DnsProtectionService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("DNS-only本地域名过滤")
                .setContentText("仅IPv4 UDP DNS；不拦截HTTPS及全部广告")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_lock_lock,
                        "停止DNS保护", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onRevoke() {
        stopSession("VPN授权已撤回或被其他VPN替换");
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopSession(null);
        super.onDestroy();
    }
}
