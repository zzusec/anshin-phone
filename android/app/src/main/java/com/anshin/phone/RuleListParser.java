package com.anshin.phone;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Parses data, never URLs or arbitrary address mappings. Invalid individual domains are ignored. */
public final class RuleListParser {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int MAX_RULES = 200000;
    public static final int MIN_RULES = 100;
    private static final String[] PROTECTED_PARENTS = {"github.com", "githubusercontent.com",
            "raw.github.com", "localhost", "local", "localdomain"};
    private static final Set<String> BUSINESS_PARENTS = new HashSet<>(Arrays.asList(
            "baidu.com", "qq.com", "mi.com", "google.com", "googleapis.com",
            "apple.com", "microsoft.com"));

    public static Set<String> parseHosts(InputStream input) throws IOException {
        return parse(input, true);
    }

    /** Explicit plain-domain API, also used for the validated private cache. */
    public static Set<String> parseDomains(InputStream input) throws IOException {
        return parse(input, false);
    }

    private static Set<String> parse(InputStream input, boolean hosts) throws IOException {
        Set<String> domains = new HashSet<>();
        InputStream limited = new FilterInputStream(input) {
            private long bytes;
            private void consumed(int count) throws IOException {
                checkInterrupted();
                if (count > 0 && (bytes += count) > MAX_BYTES) {
                    throw new IOException("规则文件超过8MB限制");
                }
            }
            @Override public int read() throws IOException {
                checkInterrupted();
                int value = in.read();
                consumed(value < 0 ? 0 : 1);
                return value;
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                checkInterrupted();
                int count = in.read(buffer, offset, (int) Math.min(length, MAX_BYTES - bytes + 1));
                consumed(count);
                return count;
            }
        };
        BufferedReader reader = new BufferedReader(new InputStreamReader(limited,
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)));
        String line;
        boolean first = true;
        while ((line = reader.readLine()) != null) {
            checkInterrupted();
            if (first && line.startsWith("\ufeff")) line = line.substring(1);
            first = false;
            if (line.length() > 8192) throw new IOException("规则文件行过长");
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment);
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] fields = line.split("\\s+");
            int start;
            if (hosts) {
                if (fields.length < 2 || !("0.0.0.0".equals(fields[0])
                        || "127.0.0.1".equals(fields[0]))) continue;
                start = 1;
            } else {
                if (fields.length != 1) continue;
                start = 0;
            }
            for (int i = start; i < fields.length; i++) {
                final String domain;
                try {
                    domain = DomainRules.normalize(fields[i]);
                } catch (IllegalArgumentException invalidDomain) {
                    continue;
                }
                if (protectedDomain(domain)) continue;
                domains.add(domain);
                if (domains.size() > MAX_RULES) throw new IOException("规则数量超过200000限制");
            }
        }
        return Collections.unmodifiableSet(domains);
    }

    private static boolean protectedDomain(String domain) {
        // Do not let a downloaded list disable its own TLS/download/update infrastructure.
        for (String parent : PROTECTED_PARENTS) {
            if (domain.equals(parent) || domain.endsWith("." + parent)) return true;
        }
        // Only protect the broad parent, not legitimate ads.qq.com / mobads.baidu.com rules.
        return BUSINESS_PARENTS.contains(domain);
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("规则更新已取消");
    }

    private RuleListParser() {}
}
