package com.anshin.phone;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Bounded IPv4/UDP DNS codec. No Android dependency and no TCP/IPv6 interception. */
public final class DnsPacket {
    public static final int MAX_DNS_LENGTH = 65507;
    public static final int NXDOMAIN = 3;
    public static final int SERVFAIL = 2;

    private final byte[] source;
    private final byte[] destination;
    private final int sourcePort;
    private final byte[] dns;
    private final Question question;

    private DnsPacket(byte[] packet, int ipLength, byte[] dns, Question question) {
        source = Arrays.copyOfRange(packet, 12, 16);
        destination = Arrays.copyOfRange(packet, 16, 20);
        sourcePort = u16(packet, ipLength);
        this.dns = dns;
        this.question = question;
    }

    /** Throws on malformed or unsupported packets. length is the actual TUN read length. */
    public static DnsPacket parse(byte[] packet, int length) {
        require(packet != null && length >= 20 && length <= packet.length,
                "Truncated IPv4 header");
        require((packet[0] & 0xf0) == 0x40, "Only IPv4 is supported");
        int ipLength = (packet[0] & 0x0f) * 4;
        require(ipLength >= 20 && ipLength <= length, "Invalid IPv4 header length");
        require(u16(packet, 2) == length, "IPv4 total length mismatch");
        require((u16(packet, 6) & 0xbfff) == 0, "Fragmented/reserved IPv4 packet");
        require((packet[9] & 0xff) == 17, "Only UDP is supported");
        require(ipv4Checksum(packet, 0, ipLength) == 0, "Invalid IPv4 checksum");
        require(length >= ipLength + 8 + 12, "Truncated UDP/DNS header");
        require(u16(packet, ipLength + 2) == 53, "Not a DNS destination port");
        require(u16(packet, ipLength) != 0, "Invalid UDP source port");
        int udpLength = u16(packet, ipLength + 4);
        require(udpLength == length - ipLength, "UDP length mismatch");
        require(u16(packet, ipLength + 6) == 0
                || udpChecksum(packet, ipLength, udpLength) == 0, "Invalid UDP checksum");
        byte[] dns = Arrays.copyOfRange(packet, ipLength + 8, length);
        int flags = u16(dns, 2);
        // Permit RD, AD and CD only; standard query, not a response or truncated query.
        require((flags & ~0x0130) == 0, "Unsupported DNS query flags");
        require(u16(dns, 4) == 1 && u16(dns, 6) == 0 && u16(dns, 8) == 0,
                "Expected a single DNS question");
        Question question = readQuestion(dns);
        validateRecords(dns, question.end, u16(dns, 10), true);
        return new DnsPacket(packet, ipLength, dns, question);
    }

    public String getDomain() {
        return question.domain;
    }

    public int getQueryType() {
        return question.type;
    }

    public byte[] getQuery() {
        return dns.clone();
    }

    public boolean isDestination(int a, int b, int c, int d) {
        return (destination[0] & 255) == a && (destination[1] & 255) == b
                && (destination[2] & 255) == c && (destination[3] & 255) == d;
    }

    /** NXDOMAIN/SERVFAIL retain the original ID, question, and RD; RA is set. */
    public byte[] errorResponse(int rcode) {
        require(rcode == NXDOMAIN || rcode == SERVFAIL, "Unsupported local response code");
        byte[] response = Arrays.copyOf(dns, question.end);
        put16(response, 2, 0x8080 | (u16(dns, 2) & 0x0100) | rcode);
        put16(response, 6, 0);
        put16(response, 8, 0);
        put16(response, 10, 0);
        return wrap(response);
    }

    /** Only accept the connected upstream's response to this ID and full question. */
    public boolean matchesResponse(byte[] response, int length) {
        try {
            require(response != null && length >= 12 && length <= response.length
                    && length <= MAX_DNS_LENGTH, "Invalid DNS response length");
            byte[] message = length == response.length ? response : Arrays.copyOf(response, length);
            int flags = u16(message, 2);
            require(u16(message, 0) == u16(dns, 0) && (flags & 0xf800) == 0x8000
                    && (flags & 0x0040) == 0 && u16(message, 4) == 1,
                    "Mismatched DNS response header");
            Question other = readQuestion(message);
            require(question.domain.equals(other.domain) && question.type == other.type,
                    "Mismatched DNS response question");
            int recordCount = u16(message, 6) + u16(message, 8) + u16(message, 10);
            validateRecords(message, other.end, recordCount, false);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public byte[] forwardedResponse(byte[] response, int length) {
        require(matchesResponse(response, length), "Invalid upstream DNS response");
        return wrap(Arrays.copyOf(response, length));
    }

    /** Internet checksum, including odd-length buffers; valid IPv4 headers yield zero. */
    public static int ipv4Checksum(byte[] data, int offset, int length) {
        require(data != null && offset >= 0 && length >= 0
                && offset <= data.length - length, "Invalid checksum bounds");
        return finishChecksum(sum(data, offset, length, 0));
    }

    private byte[] wrap(byte[] message) {
        require(message.length <= MAX_DNS_LENGTH, "DNS response too large for IPv4 UDP");
        byte[] packet = new byte[28 + message.length];
        packet[0] = 0x45;
        put16(packet, 2, packet.length);
        put16(packet, 6, 0x4000);
        packet[8] = 64;
        packet[9] = 17;
        System.arraycopy(destination, 0, packet, 12, 4);
        System.arraycopy(source, 0, packet, 16, 4);
        put16(packet, 20, 53);
        put16(packet, 22, sourcePort);
        put16(packet, 24, 8 + message.length);
        // A zero UDP checksum is explicitly permitted for IPv4; IP checksum is mandatory.
        System.arraycopy(message, 0, packet, 28, message.length);
        put16(packet, 10, ipv4Checksum(packet, 0, 20));
        return packet;
    }

    private static Question readQuestion(byte[] message) {
        int position = 12;
        int wireLength = 1;
        StringBuilder name = new StringBuilder();
        while (true) {
            require(position < message.length, "Truncated DNS question name");
            int labelLength = message[position++] & 255;
            // No compressed question names, so no pointer cycles or out-of-range pointers.
            require(labelLength <= 63, "Compressed/invalid DNS question name");
            if (labelLength == 0) {
                break;
            }
            wireLength += labelLength + 1;
            require(wireLength <= 255 && labelLength <= message.length - position,
                    "Overlong/truncated DNS name");
            if (name.length() != 0) {
                name.append('.');
            }
            for (int i = 0; i < labelLength; i++) {
                int ch = message[position + i] & 255;
                // Allow underscores used by local DNS; these never match hostname rules.
                require((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                        || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_',
                        "Unsupported DNS name character");
            }
            name.append(new String(message, position, labelLength, StandardCharsets.US_ASCII));
            position += labelLength;
        }
        require(position <= message.length - 4, "Missing DNS question");
        int type = u16(message, position);
        require((type == 1 || type == 28) && u16(message, position + 2) == 1,
                "Only IN A/AAAA questions are supported");
        return new Question(name.length() == 0 ? "." : name.toString().toLowerCase(Locale.ROOT),
                type, position + 4);
    }

    private static void validateRecords(byte[] message, int position, int count, boolean query) {
        // Queries may carry a single EDNS(0) OPT, including DNSSEC DO and bounded options.
        require(!query || count <= 1, "Unsupported DNS additional records");
        for (int record = 0; record < count; record++) {
            int nameStart = position;
            position = skipRecordName(message, position);
            require(position <= message.length - 10, "Truncated DNS record header");
            int dataLength = u16(message, position + 8);
            if (query) {
                require(message[nameStart] == 0 && u16(message, position) == 41
                        && message[position + 4] == 0 && message[position + 5] == 0,
                        "Only EDNS(0) additional data is supported");
                int option = position + 10;
                int end = option + dataLength;
                require(end <= message.length, "Truncated EDNS data");
                while (option < end) {
                    require(option <= end - 4, "Truncated EDNS option");
                    int optionLength = u16(message, option + 2);
                    option += 4;
                    require(optionLength <= end - option, "Truncated EDNS option data");
                    option += optionLength;
                }
            }
            position += 10;
            require(dataLength <= message.length - position, "Truncated DNS record data");
            position += dataLength;
        }
        require(position == message.length, "Unexpected DNS trailing data");
    }

    private static int skipRecordName(byte[] message, int position) {
        int end = -1;
        int wireLength = 1;
        int steps = 0;
        while (true) {
            require(position < message.length && ++steps <= 128, "Invalid DNS record name");
            int label = message[position++] & 255;
            if ((label & 0xc0) == 0xc0) {
                require(position < message.length, "Truncated DNS record pointer");
                int pointer = ((label & 63) << 8) | (message[position++] & 255);
                require(pointer >= 12 && pointer < position - 2, "Invalid DNS record pointer");
                if (end < 0) {
                    end = position;
                }
                position = pointer;
            } else {
                require(label <= 63, "Invalid DNS record label");
                if (label == 0) {
                    return end < 0 ? position : end;
                }
                wireLength += label + 1;
                require(wireLength <= 255 && label <= message.length - position,
                        "Overlong/truncated DNS record name");
                position += label;
            }
        }
    }

    private static int udpChecksum(byte[] packet, int ipLength, int udpLength) {
        int total = sum(packet, 12, 8, 17 + udpLength);
        return finishChecksum(sum(packet, ipLength, udpLength, total));
    }

    private static int sum(byte[] data, int offset, int length, int total) {
        for (int i = 0; i < length; i += 2) {
            total += (data[offset + i] & 255) << 8;
            if (i + 1 < length) {
                total += data[offset + i + 1] & 255;
            }
        }
        return total;
    }

    private static int finishChecksum(int total) {
        while ((total >>> 16) != 0) {
            total = (total & 65535) + (total >>> 16);
        }
        return (~total) & 65535;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }

    private static void put16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    private static void require(boolean condition, String error) {
        if (!condition) {
            throw new IllegalArgumentException(error);
        }
    }

    private static final class Question {
        final String domain;
        final int type;
        final int end;

        Question(String domain, int type, int end) {
            this.domain = domain;
            this.type = type;
            this.end = end;
        }
    }
}
