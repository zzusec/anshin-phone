package com.anshin.phone;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class RuleListParserTest {
    @Test
    public void hostsAcceptsCommentsWhitespaceAliasesIdnAndDeduplicates() throws Exception {
        Set<String> rules = hosts("\ufeff# hosts\r\n\n 0.0.0.0 ADS.example. alias.example # comment\n"
                + "127.0.0.1\tads.example\n0.0.0.0 BÜCHER.example\n"
                + "127.0.0.1 xn--bcher-kva.example localhost\n");
        assertEquals(new HashSet<>(Arrays.asList("ads.example", "alias.example",
                "xn--bcher-kva.example")), rules);
        assertThrows(UnsupportedOperationException.class, () -> rules.add("extra.example"));
    }

    @Test
    public void hostsDoesNotAcceptOtherMappingsOrAccidentallyEnablePlainDomains() throws Exception {
        assertEquals(Collections.singleton("ads.example"), hosts(
                "127.0.0.1 ads.example\n192.168.1.1 router.example\n::1 ipv6.example\n"
                + "0.0.0.1 arbitrary.example\n127.1 short.example\nads.other.example\n"
                + "0.0.0.0\n# only a comment\n"));
    }

    @Test
    public void rejectsUrlsIpsWildcardsSingleLabelsAndMalformedLabels() throws Exception {
        String[] invalid = {"com", "localhost", "127.0.0.1", "127.1", "2130706433",
                "0x7f.0.0.1", "::1", "[::1]", "https://ads.example", "ads.example/path",
                "ads.example:53", "*.ads.example", "bad_name.example", "-bad.example",
                "bad-.example", "bad..example", "ads.example..", "xn--a.example", "a@b.example"};
        StringBuilder data = new StringBuilder();
        for (String invalidDomain : invalid) data.append("0.0.0.0 ").append(invalidDomain).append('\n');
        assertTrue(hosts(data.toString()).isEmpty());
    }

    @Test
    public void protectsUpdateChannelsLocalNamesAndBroadBusinessParentsOnly() throws Exception {
        String[] protectedNames = {"github.com", "API.GITHUB.COM.", "raw.githubusercontent.com",
                "githubusercontent.com", "user.github.io.github.com", "raw.github.com",
                "localhost", "localhost.localdomain", "router.local", "x.localhost",
                "baidu.com", "qq.com", "mi.com", "google.com", "googleapis.com",
                "apple.com", "microsoft.com"};
        StringBuilder data = new StringBuilder();
        for (String domain : protectedNames) data.append("0.0.0.0 ").append(domain).append('\n');
        data.append("0.0.0.0 ads.qq.com mobads.baidu.com ad.mi.com notgithub.com github.com.evil.example\n");
        assertEquals(new HashSet<>(Arrays.asList("ads.qq.com", "mobads.baidu.com", "ad.mi.com",
                "notgithub.com", "github.com.evil.example")), hosts(data.toString()));
    }

    @Test
    public void plainDomainsRequiresExplicitApiAndOneDomainPerLine() throws Exception {
        String text = "ADS.example. # comment\nbücher.example\n127.0.0.1 host.example\n"
                + "https://bad.example\n*.bad.example\ncom\napi.github.com\n"
                + "ads.example\nfirst.example second.example\n";
        Set<String> parsed = RuleListParser.parseDomains(input(text));
        assertEquals(new HashSet<>(Arrays.asList("ads.example", "xn--bcher-kva.example")), parsed);
        assertTrue(hosts("ads.example\nbücher.example\n").isEmpty());
    }

    @Test
    public void downloadedRulesUnionBasicAndAllowWinsOnDomainBoundary() throws Exception {
        DomainRules rules = new DomainRules(hosts("0.0.0.0 ads.example\n"),
                Collections.singleton("safe.ads.example"));
        assertTrue(rules.isBlocked("doubleclick.net"));
        assertTrue(rules.isBlocked("child.ads.example"));
        assertFalse(rules.isBlocked("child.safe.ads.example"));
        assertTrue(rules.isBlocked("unsafe.ads.example"));
        assertFalse(rules.isBlocked("notads.example"));
    }

    @Test
    public void deduplicatesAReasonableMinimumSizedList() throws Exception {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < RuleListParser.MIN_RULES; i++) {
            data.append("127.0.0.1 ad").append(i).append(".example\n");
            data.append("0.0.0.0 AD").append(i).append(".EXAMPLE.\n");
        }
        assertEquals(RuleListParser.MIN_RULES, hosts(data.toString()).size());
    }

    @Test
    public void byteLimitAppliesEvenToCommentsAndBothParseModes() {
        // Short comments ensure this actually exercises the byte cap, not the per-line cap.
        InputStream endlessComments = new InputStream() {
            private int position;
            @Override public int read() {
                return (position++ & 1) == 0 ? '#' : '\n';
            }
            @Override public int read(byte[] buffer, int offset, int length) {
                for (int i = 0; i < length; i++) buffer[offset + i] = (byte) read();
                return length;
            }
        };
        IOException error = assertThrows(IOException.class, () -> RuleListParser.parseHosts(endlessComments));
        assertTrue(error.getMessage().contains("8MB"));
        byte[] tooLarge = new byte[RuleListParser.MAX_BYTES + 1];
        for (int i = 0; i < tooLarge.length; i++) tooLarge[i] = (byte) ((i & 1) == 0 ? '#' : '\n');
        assertThrows(IOException.class,
                () -> RuleListParser.parseDomains(new ByteArrayInputStream(tooLarge)));
    }

    @Test(timeout = 20000)
    public void rejectsMoreThanMaximumUniqueRulesRatherThanTruncating() {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i <= RuleListParser.MAX_RULES; i++) {
            data.append("0.0.0.0 ad").append(i).append(".example\n");
        }
        IOException error = assertThrows(IOException.class, () -> hosts(data.toString()));
        assertTrue(error.getMessage().contains("200000"));
    }

    @Test
    public void propagatesReadErrorsInvalidUtf8AndOverlongLines() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("read failed"); }
        };
        assertEquals("read failed", assertThrows(IOException.class,
                () -> RuleListParser.parseHosts(broken)).getMessage());
        assertThrows(IOException.class, () -> RuleListParser.parseHosts(
                new ByteArrayInputStream(new byte[]{(byte) 0xc3, (byte) 0x28})));
        char[] longLine = new char[8193];
        Arrays.fill(longLine, '#');
        assertThrows(IOException.class, () -> hosts(new String(longLine)));
    }

    @Test
    public void cancellationIsVisibleAndPreservesInterruptedFlag() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> hosts("0.0.0.0 ads.example\n"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void emptyCommentAndHtmlFilesDoNotProduceRules() throws Exception {
        assertTrue(hosts("").isEmpty());
        assertTrue(hosts("# header\n\n127.0.0.1 localhost\n::1 localhost\n").isEmpty());
        assertTrue(hosts("<!doctype html>\n<html>gateway error</html>\n").isEmpty());
    }

    private static Set<String> hosts(String text) throws IOException {
        return RuleListParser.parseHosts(input(text));
    }

    private static InputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
