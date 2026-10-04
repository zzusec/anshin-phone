package com.anshin.phone;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class DomainRulesTest {
    @Test
    public void normalizesCaseTrailingDotAndIdn() {
        assertEquals("example.com", DomainRules.normalize("  EXAMPLE.Com.  "));
        assertEquals("xn--bcher-kva.example", DomainRules.normalize("BÜCHER.example."));
        assertEquals("xn--bcher-kva.example", DomainRules.normalize("xn--bcher-kva.example"));
        assertEquals("example.com", DomainRules.normalize("Example\u3002COM\uff0e"));
    }

    @Test
    public void matchesExactAndSubdomainButNeverSuffixWithoutBoundary() {
        DomainRules rules = rules(new String[]{"example.com"}, new String[]{});
        assertTrue(rules.isBlocked("example.com"));
        assertTrue(rules.isBlocked("a.b.EXAMPLE.COM."));
        assertFalse(rules.isBlocked("evil-example.com"));
        assertFalse(rules.isBlocked("notexample.com"));
        assertFalse(rules.isBlocked("example.com.evil.org"));
    }

    @Test
    public void allowOverridesBlockOnItsOwnDomainBoundary() {
        DomainRules rules = rules(new String[]{"example.com", "ads.safe.example.com"},
                new String[]{"safe.example.com"});
        assertTrue(rules.isBlocked("example.com"));
        assertFalse(rules.isBlocked("safe.example.com"));
        assertFalse(rules.isBlocked("ads.safe.example.com"));
        assertTrue(rules.isBlocked("evil-safe.example.com"));
        assertTrue(rules.isBlocked("safe.example.com.evil.example.com"));
    }

    @Test
    public void parentAllowAlsoOverridesAnExplicitChildBlockAndBuiltins() {
        DomainRules rules = rules(new String[]{"ads.example.com"},
                new String[]{"example.com", "doubleclick.net"});
        assertFalse(rules.isBlocked("ads.example.com"));
        assertFalse(rules.isBlocked("x.doubleclick.net"));
    }

    @Test
    public void idnRuleMatchesBothUnicodeAndAsciiQueries() {
        DomainRules rules = rules(new String[]{"BÜCHER.example."}, new String[]{});
        assertTrue(rules.isBlocked("ads.xn--bcher-kva.example"));
        assertTrue(rules.isBlocked("ads.bücher.example"));
    }

    @Test
    public void normalizedDuplicatesAreDeduplicatedAndSetsImmutable() {
        DomainRules rules = rules(new String[]{"EXAMPLE.COM.", "example.com"},
                new String[]{"SAFE.EXAMPLE.COM.", "safe.example.com"});
        assertEquals(DomainRules.BASIC_BLOCKED_DOMAINS.size() + 1, rules.getBlockedDomains().size());
        assertEquals(1, rules.getAllowedDomains().size());
        assertThrows(UnsupportedOperationException.class,
                () -> rules.getBlockedDomains().add("another.example"));
        assertThrows(UnsupportedOperationException.class,
                () -> rules.getAllowedDomains().clear());
    }

    @Test
    public void rejectsWildcardsUrlsIpsAndMalformedLabels() {
        String[] invalid = {"", " ", ".", "..", "example.com..", "*.example.com",
                "https://example.com", "example.com/path", "example.com:53", "a@b.com",
                "127.0.0.1", "127.1", "2130706433", "0x7f000001", "0x7f.0.0.1",
                "::1", "[2001:db8::1]", "-bad.com", "bad-.com", "bad..com", "bad_name.com",
                "bad name.com", "bad\nname.com", "example.com?x=1", "xn--.com", "xn--a.com", "com", "localhost", "BÜCHER"};
        for (String domain : invalid) {
            assertThrows("Should reject: " + domain, IllegalArgumentException.class,
                    () -> DomainRules.normalize(domain));
        }
        assertThrows(IllegalArgumentException.class, () -> DomainRules.normalize(null));
        assertThrows(IllegalArgumentException.class,
                () -> rules(new String[]{"*.example.com"}, new String[]{}));
        assertThrows(IllegalArgumentException.class,
                () -> rules(new String[]{}, new String[]{"https://example.com"}));
    }

    @Test
    public void checksLabelAndTotalLengthAfterIdnConversion() {
        String label = repeat('a', 63);
        assertEquals(label + ".com", DomainRules.normalize(label + ".com"));
        assertThrows(IllegalArgumentException.class,
                () -> DomainRules.normalize(repeat('a', 64) + ".com"));
        String max = label + "." + label + "." + label + "." + repeat('b', 61);
        assertEquals(253, max.length());
        assertEquals(max, DomainRules.normalize(max + "."));
        assertThrows(IllegalArgumentException.class, () -> DomainRules.normalize(max + "b"));
        assertThrows(IllegalArgumentException.class,
                () -> DomainRules.normalize(repeat('ü', 60) + ".example"));
    }

    @Test
    public void basicListIsLimitedAndDoesNotBlockBusinessParentDomains() {
        DomainRules rules = new DomainRules();
        for (String domain : new String[]{"doubleclick.net", "googlesyndication.com",
                "ads.qq.com", "mobads.baidu.com", "ad.mi.com"}) {
            assertTrue(rules.isBlocked(domain));
            assertTrue(rules.isBlocked("child." + domain));
        }
        for (String domain : new String[]{"qq.com", "www.qq.com", "baidu.com", "www.baidu.com",
                "mi.com", "account.mi.com", "example.com", "bad_name.com", "127.0.0.1"}) {
            assertFalse(rules.isBlocked(domain));
        }
    }

    @Test
    public void rejectsNullCollectionsRatherThanSilentlyIgnoringConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new DomainRules(null, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> new DomainRules(Collections.emptyList(), null));
    }

    @Test(timeout = 20000)
    public void largeListUsesDomainBoundariesAndAllowsWithoutScanningAllRules() {
        Set<String> blocks = new HashSet<>();
        for (int i = 0; i < 200000; i++) blocks.add("ad" + i + ".vendor.example");
        DomainRules rules = new DomainRules(blocks,
                Collections.singleton("safe.ad199999.vendor.example"));
        assertEquals(200000 + DomainRules.BASIC_BLOCKED_DOMAINS.size(),
                rules.getBlockedDomains().size());
        for (int i = 0; i < 10000; i++) {
            assertTrue(rules.isBlocked("child.ad199999.vendor.example"));
            assertFalse(rules.isBlocked("safe.ad199999.vendor.example"));
            assertFalse(rules.isBlocked("child.safe.ad199999.vendor.example"));
            assertFalse(rules.isBlocked("notad199999.vendor.example"));
            assertFalse(rules.isBlocked("ad199999.vendor.example.other.example"));
        }
        assertTrue(rules.isBlocked("unsafe.ad199999.vendor.example"));
        assertThrows(UnsupportedOperationException.class, () -> rules.getBlockedDomains().clear());
    }

    private static DomainRules rules(String[] blocks, String[] allows) {
        return new DomainRules(Arrays.asList(blocks), Arrays.asList(allows));
    }

    private static String repeat(char ch, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, ch);
        return new String(chars);
    }
}
