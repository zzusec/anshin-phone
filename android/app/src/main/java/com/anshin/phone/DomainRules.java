package com.anshin.phone;

import java.net.IDN;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Immutable basic, downloaded and user rules with allow-first, label-boundary matching. */
public final class DomainRules {
    public static final Set<String> BASIC_BLOCKED_DOMAINS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("doubleclick.net", "googlesyndication.com",
                    "ads.qq.com", "mobads.baidu.com", "ad.mi.com")));

    private final Set<String> blocked;
    private final Set<String> allowed;

    public DomainRules() {
        this(Collections.emptyList(), Collections.emptyList());
    }

    /** Adds user rules to the basic list. Normalized duplicates are explicitly deduplicated. */
    public DomainRules(Collection<String> blockedDomains, Collection<String> allowedDomains) {
        HashSet<String> blocks = normalizeAll(blockedDomains);
        blocks.addAll(BASIC_BLOCKED_DOMAINS);
        blocked = Collections.unmodifiableSet(blocks);
        allowed = Collections.unmodifiableSet(normalizeAll(allowedDomains));
    }

    public Set<String> getBlockedDomains() {
        return blocked;
    }

    public Set<String> getAllowedDomains() {
        return allowed;
    }

    /** Invalid query names cannot match rules; invalid configured rules instead throw. */
    public boolean isBlocked(String domain) {
        final String normalized;
        try {
            normalized = normalize(domain);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return !matches(normalized, allowed) && matches(normalized, blocked);
    }

    public static String normalize(String domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain must not be null");
        }
        // IDN recognizes these Unicode DNS separators, too.
        String value = domain.trim().replace('\u3002', '.').replace('\uff0e', '.')
                .replace('\uff61', '.');
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty() || value.length() > 253 || value.contains("*")
                || value.contains(":") || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("Expected a domain, not a URL, wildcard or IP");
        }
        String ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (ascii.length() > 253 || ascii.indexOf('.') < 0) {
            throw new IllegalArgumentException("Expected a multi-label domain of at most 253 characters");
        }
        boolean numericAddress = true;
        for (String label : ascii.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-")
                    || label.endsWith("-") || !label.matches("[a-z0-9-]+")
                    || (label.startsWith("xn--") && IDN.toUnicode(label).equals(label))) {
                throw new IllegalArgumentException("Invalid domain label");
            }
            numericAddress &= label.matches("[0-9]+|0x[0-9a-f]+");
        }
        if (numericAddress) {
            throw new IllegalArgumentException("IP literals are not domain rules");
        }
        return ascii;
    }

    private static HashSet<String> normalizeAll(Collection<String> domains) {
        if (domains == null) {
            throw new IllegalArgumentException("Rules must not be null");
        }
        HashSet<String> result = new HashSet<>();
        for (String domain : domains) {
            result.add(normalize(domain));
        }
        return result;
    }

    // HashSet lookups per label, never a scan of the potentially 200000-entry list.
    private static boolean matches(String domain, Set<String> rules) {
        String suffix = domain;
        while (true) {
            if (rules.contains(suffix)) {
                return true;
            }
            int dot = suffix.indexOf('.');
            if (dot < 0) {
                return false;
            }
            suffix = suffix.substring(dot + 1);
        }
    }
}
