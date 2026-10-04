package com.anshin.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/** Single trusted source; private, validated last-good cache. No DNS/history leaves this module. */
public final class RuleListStore {
    public static final String SOURCE_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final String TAG = "RuleListStore";
    private static final Object LOCK = new Object();
    private static final Set<String> REDIRECT_HOSTS = new HashSet<>(Arrays.asList(
            "raw.githubusercontent.com", "github.com", "raw.github.com", "githubusercontent.com"));
    private static File cachedFile;
    private static Set<String> cachedRules = Collections.emptySet();
    private static Thread owner;
    private static boolean cancelled;

    public static JSONObject status(Context context) {
        synchronized (LOCK) {
            ensureLoaded(context);
            SharedPreferences prefs = preferences(context);
            JSONObject result = new JSONObject();
            try {
                result.put("sourceUrl", SOURCE_URL);
                result.put("count", cachedRules.size());
                result.put("lastSuccess", prefs.getLong("lastSuccess", 0));
                result.put("lastCheck", prefs.getLong("lastCheck", 0));
                result.put("error", prefs.getString("error", ""));
                result.put("updating", owner != null);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            return result;
        }
    }

    public static DomainRules load(Context context, Collection<String> customBlocked,
                                   Collection<String> allowed) {
        final Set<String> downloaded;
        synchronized (LOCK) {
            ensureLoaded(context);
            downloaded = cachedRules;
        }
        if (customBlocked == null) throw new IllegalArgumentException("Rules must not be null");
        Set<String> combined = new HashSet<>(downloaded);
        combined.addAll(customBlocked);
        return new DomainRules(combined, allowed);
    }

    public static boolean hasValidRules(Context context) {
        synchronized (LOCK) {
            ensureLoaded(context);
            return cachedRules.size() >= RuleListParser.MIN_RULES;
        }
    }

    /** Synchronous; the caller must use a background thread and handle IOException. */
    public static void refresh(Context context) throws IOException {
        File target = rulesFile(context);
        SharedPreferences prefs = preferences(context);
        synchronized (LOCK) {
            RuleListParser.checkInterrupted();
            if (owner != null) throw new IOException("规则更新正在进行");
            owner = Thread.currentThread();
            cancelled = false;
        }
        File pending = new File(target.getParentFile(), target.getName() + ".pending");
        try {
            prefs.edit().putLong("lastCheck", System.currentTimeMillis()).putString("error", "").apply();
            Set<String> downloaded = download();
            validateCount(downloaded);
            checkCancelled();
            File directory = target.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("无法创建规则缓存目录");
            }
            try (FileOutputStream output = new FileOutputStream(pending);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                for (String domain : downloaded) {
                    checkCancelled();
                    writer.write(domain);
                    writer.newLine();
                }
                writer.flush();
                output.getFD().sync();
            }
            Set<String> validated;
            try (InputStream input = new FileInputStream(pending)) {
                validated = RuleListParser.parseDomains(input);
            }
            validateCount(validated);
            if (!validated.equals(downloaded)) throw new IOException("规则缓存校验不一致");
            // Cancellation and the atomic commit share a lock. A stopped job cannot commit later.
            synchronized (LOCK) {
                checkCancelled();
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                cachedFile = target;
                cachedRules = validated;
                prefs.edit().putLong("lastSuccess", System.currentTimeMillis())
                        .putString("error", "").apply();
            }
        } catch (IOException | RuntimeException failure) {
            String message = describe(failure);
            synchronized (LOCK) {
                prefs.edit().putString("error", message).apply();
            }
            Log.w(TAG, message, failure);
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException(message, failure);
        } finally {
            if (pending.exists() && !pending.delete()) Log.w(TAG, "无法清理未提交规则缓存");
            synchronized (LOCK) {
                owner = null;
                cancelled = false;
            }
        }
    }

    /** Called by JobService before it releases a stopped job; also interrupts a not-yet-started worker. */
    static void cancelRefresh(Thread worker) {
        synchronized (LOCK) {
            if (owner == worker) cancelled = true;
            worker.interrupt();
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        RuleListParser.checkInterrupted();
        synchronized (LOCK) {
            if (cancelled) throw new InterruptedIOException("规则更新已取消");
        }
    }

    private static Set<String> download() throws IOException {
        URL url = new URL(SOURCE_URL);
        long deadline = System.nanoTime() + 90_000_000_000L;
        for (int redirects = 0; redirects <= 3; redirects++) {
            checkDownloadTime(deadline);
            validateUrl(url);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            // Keep platform TLS/hostname verification intact; never install a custom trust manager.
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int response = connection.getResponseCode();
                checkDownloadTime(deadline);
                if (response == 301 || response == 302 || response == 303
                        || response == 307 || response == 308) {
                    String location = connection.getHeaderField("Location");
                    if (redirects == 3 || location == null) throw new IOException("规则下载重定向无效或过多");
                    url = new URL(url, location);
                    validateUrl(url);
                    continue;
                }
                if (response != HttpsURLConnection.HTTP_OK) throw new IOException("规则下载HTTP " + response);
                if (connection.getContentLengthLong() > RuleListParser.MAX_BYTES) {
                    throw new IOException("规则文件超过8MB限制");
                }
                String encoding = connection.getContentEncoding();
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                    throw new IOException("规则下载使用不支持的内容编码");
                }
                try (InputStream input = connection.getInputStream();
                     InputStream boundedTime = new FilterInputStream(input) {
                         private void checkTime() throws IOException {
                             checkDownloadTime(deadline);
                         }
                         @Override public int read() throws IOException {
                             checkTime();
                             int value = in.read();
                             checkTime();
                             return value;
                         }
                         @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                             checkTime();
                             int count = in.read(buffer, offset, length);
                             checkTime();
                             return count;
                         }
                     }) {
                    return RuleListParser.parseHosts(boundedTime);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("规则下载重定向过多");
    }

    private static void checkDownloadTime(long deadline) throws IOException {
        checkCancelled();
        if (System.nanoTime() >= deadline) throw new IOException("规则下载总超时");
    }

    private static void validateUrl(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null
                || url.getRef() != null || (url.getPort() != -1 && url.getPort() != 443)
                || !REDIRECT_HOSTS.contains(url.getHost().toLowerCase(java.util.Locale.ROOT))) {
            throw new IOException("拒绝非HTTPS或非GitHub官方域名的规则重定向");
        }
    }

    private static void validateCount(Set<String> rules) throws IOException {
        if (rules.size() < RuleListParser.MIN_RULES) throw new IOException("有效规则不足100条，保留旧规则");
    }

    // The first load validates disk data once; status polling never reparses a large list.
    private static void ensureLoaded(Context context) {
        File file = rulesFile(context);
        if (file.equals(cachedFile)) return;
        cachedRules = Collections.emptySet();
        if (file.exists()) {
            try (InputStream input = new FileInputStream(file)) {
                Set<String> parsed = RuleListParser.parseDomains(input);
                validateCount(parsed);
                cachedRules = parsed;
            } catch (IOException | RuntimeException failure) {
                String error = "规则缓存读取失败：" + describe(failure);
                preferences(context).edit().putString("error", error).apply();
                Log.w(TAG, error, failure);
            }
        }
        cachedFile = file;
    }

    private static File rulesFile(Context context) {
        return new File(new File(context.getFilesDir(), "ad-rules"), "stevenblack.domains");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("ad-rule-list", Context.MODE_PRIVATE);
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    private RuleListStore() {}
}
