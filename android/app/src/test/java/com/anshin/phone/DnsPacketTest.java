package com.anshin.phone;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

public class DnsPacketTest {
    @Test
    public void ipv4ChecksumMatchesKnownVectorAndHandlesOddLengths() {
        byte[] header = hex("450000730000400040110000c0a80001c0a800c7");
        assertEquals(0xb861, DnsPacket.ipv4Checksum(header, 0, header.length));
        put16(header, 10, 0xb861);
        assertEquals(0, DnsPacket.ipv4Checksum(header, 0, header.length));
        assertEquals(0xfbfd, DnsPacket.ipv4Checksum(new byte[]{1, 2, 3}, 0, 3));
        assertThrows(IllegalArgumentException.class,
                () -> DnsPacket.ipv4Checksum(header, -1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> DnsPacket.ipv4Checksum(header, 0, Integer.MAX_VALUE));
    }

    @Test
    public void parsesAAndAaaaIncludingIpv4OptionsAndReadLength() {
        for (int type : new int[]{1, 28}) {
            for (int ipLength : new int[]{20, 24, 60}) {
                byte[] packet = packet(query("WWW.Example.com", type), ipLength);
                byte[] padded = Arrays.copyOf(packet, packet.length + 40);
                DnsPacket parsed = DnsPacket.parse(padded, packet.length);
                assertEquals("www.example.com", parsed.getDomain());
                assertEquals(type, parsed.getQueryType());
                assertTrue(parsed.isDestination(10, 77, 0, 2));
                assertFalse(parsed.isDestination(10, 77, 0, 3));
                byte[] dns = parsed.getQuery();
                dns[0] = 0;
                assertEquals(0x1234, u16(parsed.getQuery(), 0));
            }
        }
    }

    @Test
    public void nxdomainSwapsUdpAddressesAndPortsPreservesIdQuestionAndRd() {
        DnsPacket parsed = parse(query("ads.example.com", 1));
        byte[] original = parsed.getQuery();
        byte[] response = parsed.errorResponse(DnsPacket.NXDOMAIN);
        assertEquals(0x45, response[0] & 255);
        assertArrayEquals(new byte[]{10, 77, 0, 2}, Arrays.copyOfRange(response, 12, 16));
        assertArrayEquals(new byte[]{10, 77, 0, 1}, Arrays.copyOfRange(response, 16, 20));
        assertEquals(53, u16(response, 20));
        assertEquals(45678, u16(response, 22));
        assertEquals(response.length, u16(response, 2));
        assertEquals(response.length - 20, u16(response, 24));
        assertEquals(0, u16(response, 26));
        assertEquals(0, checksum(response, 0, 20));
        assertEquals(0x1234, u16(response, 28));
        assertEquals(0x8183, u16(response, 30));
        assertEquals(1, u16(response, 32));
        assertEquals(0, u16(response, 34));
        assertEquals(0, u16(response, 36));
        assertEquals(0, u16(response, 38));
        assertArrayEquals(Arrays.copyOfRange(original, 12, original.length),
                Arrays.copyOfRange(response, 40, response.length));
        assertTrue(parsed.matchesResponse(Arrays.copyOfRange(response, 28, response.length),
                response.length - 28));
    }

    @Test
    public void servfailPreservesDisabledRdAndDoesNotClaimAuthoritativeOrDnssecAnswer() {
        byte[] dns = query("example.com", 28);
        put16(dns, 2, 0x0030); // AD/CD are query flags, not proof of a signed local response.
        byte[] response = parse(dns).errorResponse(DnsPacket.SERVFAIL);
        assertEquals(0x8082, u16(response, 30));
        assertThrows(IllegalArgumentException.class, () -> parse(dns).errorResponse(0));
    }

    @Test
    public void forwardsOnlyMatchingIdNameTypeClassAndResponseHeader() {
        DnsPacket parsed = parse(query("www.example.com", 1));
        byte[] answer = answer(parsed);
        assertTrue(parsed.matchesResponse(answer, answer.length));
        byte[] wrapped = parsed.forwardedResponse(answer, answer.length);
        assertArrayEquals(answer, Arrays.copyOfRange(wrapped, 28, wrapped.length));
        assertEquals(0, checksum(wrapped, 0, 20));
        assertEquals(wrapped.length - 20, u16(wrapped, 24));
        byte[] mixedCase = answer.clone();
        mixedCase[13] = 'W';
        assertTrue(parsed.matchesResponse(mixedCase, mixedCase.length));
        byte[] changedId = answer.clone();
        changedId[0] ^= 1;
        rejectResponse(parsed, changedId);
        byte[] changedName = answer.clone();
        changedName[13] = 'x';
        rejectResponse(parsed, changedName);
        byte[] changedType = answer.clone();
        put16(changedType, parsed.getQuery().length - 4, 28);
        rejectResponse(parsed, changedType);
        byte[] changedClass = answer.clone();
        put16(changedClass, parsed.getQuery().length - 2, 3);
        rejectResponse(parsed, changedClass);
        byte[] notResponse = answer.clone();
        notResponse[2] &= 0x7f;
        rejectResponse(parsed, notResponse);
        byte[] opcode = answer.clone();
        opcode[2] |= 8;
        rejectResponse(parsed, opcode);
        byte[] twoQuestions = answer.clone();
        put16(twoQuestions, 4, 2);
        rejectResponse(parsed, twoQuestions);
    }

    @Test
    public void responseLengthCountsAndCompressionAreBounded() {
        DnsPacket parsed = parse(query("example.com", 1));
        byte[] response = answer(parsed);
        assertFalse(parsed.matchesResponse(null, 0));
        assertFalse(parsed.matchesResponse(response, -1));
        assertFalse(parsed.matchesResponse(response, response.length + 1));
        assertFalse(parsed.matchesResponse(new byte[65508], 65508));
        rejectResponse(parsed, Arrays.copyOf(response, response.length - 1));
        byte[] absurdCount = response.clone();
        put16(absurdCount, 6, 65535);
        rejectResponse(parsed, absurdCount);
        byte[] compressedQuestion = response.clone();
        compressedQuestion[12] = (byte) 0xc0;
        compressedQuestion[13] = 12;
        rejectResponse(parsed, compressedQuestion);
        int rrOffset = parsed.getQuery().length;
        byte[] selfPointer = response.clone();
        put16(selfPointer, rrOffset, 0xc000 | rrOffset);
        rejectResponse(parsed, selfPointer);
        byte[] outOfRange = response.clone();
        put16(outOfRange, rrOffset, 0xffff);
        rejectResponse(parsed, outOfRange);
        byte[] oversizedData = response.clone();
        put16(oversizedData, rrOffset + 10, 65535);
        rejectResponse(parsed, oversizedData);
        rejectResponse(parsed, Arrays.copyOf(response, response.length + 1));
        byte[] padding = Arrays.copyOf(response, response.length + 10);
        assertTrue(parsed.matchesResponse(padding, response.length));
    }

    @Test
    public void acceptsWellFormedTruncatedAnswerWithoutPretendingTcpSupport() {
        DnsPacket parsed = parse(query("example.com", 1));
        byte[] response = parsed.getQuery();
        put16(response, 2, 0x8380); // QR, TC, RD, RA; no incomplete resource records.
        assertTrue(parsed.matchesResponse(response, response.length));
        assertEquals(0x8380, u16(parsed.forwardedResponse(response, response.length), 30));
    }

    @Test
    public void acceptsEdnsAndStripsItFromLocalErrorResponse() {
        byte[] query = query("example.com", 28);
        byte[] edns = Arrays.copyOf(query, query.length + 15);
        put16(edns, 10, 1);
        int pos = query.length;
        edns[pos] = 0;
        put16(edns, pos + 1, 41);
        put16(edns, pos + 3, 1232);
        put16(edns, pos + 7, 0x8000); // DNSSEC DO
        put16(edns, pos + 9, 4);
        put16(edns, pos + 11, 12); // empty padding option
        DnsPacket parsed = parse(edns);
        assertArrayEquals(edns, parsed.getQuery());
        byte[] failure = parsed.errorResponse(DnsPacket.SERVFAIL);
        assertEquals(query.length + 28, failure.length);
        assertEquals(0, u16(failure, 38));
        byte[] malformedOption = edns.clone();
        put16(malformedOption, pos + 13, 1);
        reject(packet(malformedOption, 20));
        byte[] badVersion = edns.clone();
        badVersion[pos + 6] = 1;
        reject(packet(badVersion, 20));
        byte[] wrongRecord = edns.clone();
        put16(wrongRecord, pos + 1, 1);
        reject(packet(wrongRecord, 20));
    }

    @Test
    public void checksIpv4TotalHeaderAndUdpLengthsAndChecksums() {
        byte[] valid = packet(query("example.com", 1), 20);
        reject(null);
        for (int length = 0; length < valid.length; length++) {
            final int size = length;
            assertThrows(IllegalArgumentException.class, () -> DnsPacket.parse(valid, size));
        }
        assertThrows(IllegalArgumentException.class, () -> DnsPacket.parse(valid, valid.length + 1));
        byte[] wrongTotal = valid.clone();
        put16(wrongTotal, 2, valid.length - 1);
        fixIp(wrongTotal);
        reject(wrongTotal);
        byte[] smallIhl = valid.clone();
        smallIhl[0] = 0x44;
        reject(smallIhl);
        byte[] hugeIhl = Arrays.copyOf(valid, 30);
        hugeIhl[0] = 0x4f;
        reject(hugeIhl);
        byte[] wrongUdpLength = valid.clone();
        put16(wrongUdpLength, 24, valid.length - 21);
        reject(wrongUdpLength);
        byte[] shortUdp = valid.clone();
        put16(shortUdp, 24, 7);
        reject(shortUdp);
        byte[] corruptedHeader = valid.clone();
        corruptedHeader[8] ^= 1;
        reject(corruptedHeader);
    }

    @Test
    public void acceptsOptionalUdpChecksumAndRejectsCorruption() {
        byte[] checked = packet(query("example.com", 1), 24);
        put16(checked, 30, udpChecksum(checked));
        assertEquals("example.com", DnsPacket.parse(checked, checked.length).getDomain());
        byte[] corrupted = checked.clone();
        corrupted[corrupted.length - 1] ^= 1;
        reject(corrupted);
        byte[] noChecksum = checked.clone();
        put16(noChecksum, 30, 0);
        assertEquals("example.com", DnsPacket.parse(noChecksum, noChecksum.length).getDomain());
    }

    @Test
    public void rejectsFragmentsReservedFlagTcpIpv6AndWrongPorts() {
        byte[] valid = packet(query("example.com", 1), 20);
        for (int flags : new int[]{0x2000, 1, 0x8000, 0x6001}) {
            byte[] bad = valid.clone();
            put16(bad, 6, flags);
            fixIp(bad);
            reject(bad);
        }
        byte[] tcp = valid.clone();
        tcp[9] = 6;
        fixIp(tcp);
        reject(tcp);
        byte[] ipv6 = valid.clone();
        ipv6[0] = 0x60;
        reject(ipv6);
        byte[] wrongPort = valid.clone();
        put16(wrongPort, 22, 853);
        reject(wrongPort);
        byte[] zeroSource = valid.clone();
        put16(zeroSource, 20, 0);
        reject(zeroSource);
    }

    @Test
    public void rejectsCompressedTruncatedOverlongAndUnsupportedQuestions() {
        byte[] ordinary = query("example.com", 1);
        for (int count : new int[]{0, 2, 65535}) {
            byte[] bad = ordinary.clone();
            put16(bad, 4, count);
            reject(packet(bad, 20));
        }
        for (int flags : new int[]{0x8100, 0x0900, 0x0300, 0x0140, 0x0180}) {
            byte[] bad = ordinary.clone();
            put16(bad, 2, flags);
            reject(packet(bad, 20));
        }
        byte[] compressed = ordinary.clone();
        compressed[12] = (byte) 0xc0;
        compressed[13] = 12;
        reject(packet(compressed, 20));
        byte[] invalidLabel = ordinary.clone();
        invalidLabel[12] = 64;
        reject(packet(invalidLabel, 20));
        byte[] truncated = ordinary.clone();
        truncated[12] = 63;
        reject(packet(truncated, 20));
        byte[] maliciousChar = ordinary.clone();
        maliciousChar[13] = 0;
        reject(packet(maliciousChar, 20));
        reject(packet(query("example.com", 15), 20));
        byte[] wrongClass = ordinary.clone();
        put16(wrongClass, wrongClass.length - 2, 3);
        reject(packet(wrongClass, 20));
        reject(packet(Arrays.copyOf(ordinary, ordinary.length - 1), 20));
        reject(packet(Arrays.copyOf(ordinary, ordinary.length + 1), 20));
        String label = repeat('a', 63);
        String max = label + "." + label + "." + label + "." + repeat('a', 61);
        assertEquals(max, parse(query(max, 1)).getDomain());
        reject(packet(query(max + "aa", 1), 20));
        assertEquals(".", parse(query("", 1)).getDomain());
    }

    @Test
    public void randomMalformedPacketsNeverEscapeBoundsChecks() {
        Random random = new Random(7728);
        byte[] original = packet(query("example.com", 1), 24);
        for (int trial = 0; trial < 3000; trial++) {
            byte[] mutated = Arrays.copyOf(original, random.nextInt(original.length + 30));
            for (int i = 0; i < 4 && mutated.length > 0; i++) {
                mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
            }
            try {
                DnsPacket parsed = DnsPacket.parse(mutated, mutated.length);
                assertEquals(0, checksum(parsed.errorResponse(DnsPacket.NXDOMAIN), 0, 20));
            } catch (IllegalArgumentException expected) {
                // Only a controlled rejection is acceptable, never an indexing exception.
            }
        }
    }

    private static DnsPacket parse(byte[] dns) {
        byte[] packet = packet(dns, 20);
        return DnsPacket.parse(packet, packet.length);
    }

    private static void reject(byte[] packet) {
        assertThrows(IllegalArgumentException.class,
                () -> DnsPacket.parse(packet, packet == null ? 0 : packet.length));
    }

    private static void rejectResponse(DnsPacket query, byte[] response) {
        assertFalse(query.matchesResponse(response, response.length));
        assertThrows(IllegalArgumentException.class,
                () -> query.forwardedResponse(response, response.length));
    }

    private static byte[] query(String name, int type) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] header = new byte[12];
        put16(header, 0, 0x1234);
        put16(header, 2, 0x0100);
        put16(header, 4, 1);
        output.write(header, 0, header.length);
        if (!name.isEmpty()) {
            for (String label : name.split("\\.")) {
                byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
                output.write(bytes.length);
                output.write(bytes, 0, bytes.length);
            }
        }
        output.write(0);
        output.write(type >>> 8);
        output.write(type & 255);
        output.write(0);
        output.write(1);
        return output.toByteArray();
    }

    private static byte[] answer(DnsPacket parsed) {
        byte[] original = parsed.getQuery();
        byte[] response = Arrays.copyOf(original, original.length + 16);
        put16(response, 2, 0x8180);
        put16(response, 6, 1);
        byte[] record = hex("c00c000100010000003c000401020304");
        System.arraycopy(record, 0, response, original.length, record.length);
        return response;
    }

    private static byte[] packet(byte[] dns, int ipLength) {
        byte[] result = new byte[ipLength + 8 + dns.length];
        result[0] = (byte) (0x40 | ipLength / 4);
        put16(result, 2, result.length);
        put16(result, 6, 0x4000);
        result[8] = 64;
        result[9] = 17;
        System.arraycopy(new byte[]{10, 77, 0, 1, 10, 77, 0, 2}, 0, result, 12, 8);
        // NOP options, followed by EOL, are legal; codec must honor IHL, not assume 20.
        Arrays.fill(result, 20, ipLength, (byte) 1);
        if (ipLength > 20) {
            result[ipLength - 1] = 0;
        }
        put16(result, ipLength, 45678);
        put16(result, ipLength + 2, 53);
        put16(result, ipLength + 4, 8 + dns.length);
        System.arraycopy(dns, 0, result, ipLength + 8, dns.length);
        fixIp(result);
        return result;
    }

    private static void fixIp(byte[] packet) {
        put16(packet, 10, 0);
        put16(packet, 10, checksum(packet, 0, (packet[0] & 15) * 4));
    }

    // Independent reference checksum, rather than using the implementation under test.
    private static int checksum(byte[] data, int offset, int length) {
        long sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (long) (data[offset + i] & 255) << (i % 2 == 0 ? 8 : 0);
        }
        while (sum > 65535) {
            sum = (sum & 65535) + (sum >> 16);
        }
        return (int) (~sum & 65535);
    }

    private static int udpChecksum(byte[] packet) {
        int ipLength = (packet[0] & 15) * 4;
        int udpLength = packet.length - ipLength;
        byte[] pseudo = new byte[12 + udpLength];
        System.arraycopy(packet, 12, pseudo, 0, 8);
        pseudo[9] = 17;
        put16(pseudo, 10, udpLength);
        System.arraycopy(packet, ipLength, pseudo, 12, udpLength);
        put16(pseudo, 18, 0);
        int sum = checksum(pseudo, 0, pseudo.length);
        return sum == 0 ? 65535 : sum;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }

    private static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    private static byte[] hex(String text) {
        byte[] result = new byte[text.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static String repeat(char ch, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, ch);
        return new String(chars);
    }
}
