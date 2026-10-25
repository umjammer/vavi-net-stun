/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.stun;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;


/**
 * A STUN attribute (TLV).
 *
 * @param type attribute type
 * @param value attribute value without padding
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @see "RFC 8489 (5389) section 14, RFC 5780 section 7, RFC 3489 section 11.2"
 */
public record StunAttribute(int type, byte[] value) {

    // comprehension-required range (0x0000 - 0x7fff)

    /** RFC 8489 */
    public static final int MAPPED_ADDRESS = 0x0001;
    /** RFC 3489, deprecated */
    public static final int RESPONSE_ADDRESS = 0x0002;
    /** RFC 5780 (RFC 3489) */
    public static final int CHANGE_REQUEST = 0x0003;
    /** RFC 3489, deprecated */
    public static final int SOURCE_ADDRESS = 0x0004;
    /** RFC 3489, deprecated, replaced by {@link #OTHER_ADDRESS} */
    public static final int CHANGED_ADDRESS = 0x0005;
    /** RFC 8489 */
    public static final int USERNAME = 0x0006;
    /** RFC 8489 */
    public static final int MESSAGE_INTEGRITY = 0x0008;
    /** RFC 8489 */
    public static final int ERROR_CODE = 0x0009;
    /** RFC 8489 */
    public static final int UNKNOWN_ATTRIBUTES = 0x000a;
    /** RFC 8489 */
    public static final int REALM = 0x0014;
    /** RFC 8489 */
    public static final int NONCE = 0x0015;
    /** RFC 8489 */
    public static final int XOR_MAPPED_ADDRESS = 0x0020;
    /** RFC 5780 */
    public static final int PADDING = 0x0026;
    /** RFC 5780 */
    public static final int RESPONSE_PORT = 0x0027;

    // comprehension-optional range (0x8000 - 0xffff)

    /** RFC 8489 */
    public static final int SOFTWARE = 0x8022;
    /** RFC 8489 */
    public static final int ALTERNATE_SERVER = 0x8023;
    /** RFC 8489 */
    public static final int FINGERPRINT = 0x8028;
    /** RFC 5780 */
    public static final int RESPONSE_ORIGIN = 0x802b;
    /** RFC 5780 */
    public static final int OTHER_ADDRESS = 0x802c;

    /** CHANGE-REQUEST flag "change IP" */
    private static final int CHANGE_IP_FLAG = 0x04;
    /** CHANGE-REQUEST flag "change port" */
    private static final int CHANGE_PORT_FLAG = 0x02;

    private static final int FAMILY_IPV4 = 0x01;
    private static final int FAMILY_IPV6 = 0x02;

    public StunAttribute {
        if (type < 0 || type > 0xffff) {
            throw new IllegalArgumentException("type: " + type);
        }
        if (value.length > 0xffff) {
            throw new IllegalArgumentException("too long value: " + value.length);
        }
    }

    /** @return true when an agent that does not understand this attribute must reject the message */
    public boolean isComprehensionRequired() {
        return type < 0x8000;
    }

    // ---- factories

    /** for MAPPED-ADDRESS, RESPONSE-ORIGIN, OTHER-ADDRESS, ALTERNATE-SERVER, ... */
    public static StunAttribute address(int type, InetSocketAddress address) {
        byte[] ip = address.getAddress().getAddress();
        ByteBuffer bb = ByteBuffer.allocate(4 + ip.length);
        bb.put((byte) 0);
        bb.put((byte) (ip.length == 4 ? FAMILY_IPV4 : FAMILY_IPV6));
        bb.putShort((short) address.getPort());
        bb.put(ip);
        return new StunAttribute(type, bb.array());
    }

    /**
     * XOR-MAPPED-ADDRESS.
     *
     * @param transactionId 96 bit transaction id
     */
    public static StunAttribute xorAddress(int type, InetSocketAddress address, byte[] transactionId) {
        StunAttribute plain = address(type, address);
        xor(plain.value, transactionId);
        return plain;
    }

    /** CHANGE-REQUEST */
    public static StunAttribute changeRequest(boolean changeIp, boolean changePort) {
        int flags = (changeIp ? CHANGE_IP_FLAG : 0) | (changePort ? CHANGE_PORT_FLAG : 0);
        return new StunAttribute(CHANGE_REQUEST, new byte[] {0, 0, 0, (byte) flags});
    }

    /** ERROR-CODE */
    public static StunAttribute errorCode(int code, String reason) {
        if (code < 300 || code > 699) {
            throw new IllegalArgumentException("error code: " + code);
        }
        byte[] r = reason.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(4 + r.length);
        bb.putShort((short) 0);
        bb.put((byte) (code / 100));
        bb.put((byte) (code % 100));
        bb.put(r);
        return new StunAttribute(ERROR_CODE, bb.array());
    }

    /** UNKNOWN-ATTRIBUTES */
    public static StunAttribute unknownAttributes(int... types) {
        ByteBuffer bb = ByteBuffer.allocate(types.length * 2);
        for (int t : types) {
            bb.putShort((short) t);
        }
        return new StunAttribute(UNKNOWN_ATTRIBUTES, bb.array());
    }

    /** for SOFTWARE, USERNAME, REALM, NONCE */
    public static StunAttribute string(int type, String value) {
        return new StunAttribute(type, value.getBytes(StandardCharsets.UTF_8));
    }

    /** RESPONSE-PORT */
    public static StunAttribute responsePort(int port) {
        return new StunAttribute(RESPONSE_PORT, new byte[] {(byte) (port >> 8), (byte) port, 0, 0});
    }

    // ---- accessors

    /** decodes an address type attribute */
    public InetSocketAddress asAddress() throws StunException {
        if (value.length < 4) {
            throw new StunException("too short address attribute: " + this);
        }
        int family = value[1] & 0xff;
        int port = ((value[2] & 0xff) << 8) | (value[3] & 0xff);
        int length = switch (family) {
            case FAMILY_IPV4 -> 4;
            case FAMILY_IPV6 -> 16;
            default -> throw new StunException("unknown address family: " + family);
        };
        if (value.length != 4 + length) {
            throw new StunException("illegal address attribute length: " + this);
        }
        try {
            return new InetSocketAddress(InetAddress.getByAddress(Arrays.copyOfRange(value, 4, 4 + length)), port);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e); // never happen, length is checked
        }
    }

    /** decodes XOR-MAPPED-ADDRESS */
    public InetSocketAddress asXorAddress(byte[] transactionId) throws StunException {
        byte[] v = value.clone();
        xor(v, transactionId);
        return new StunAttribute(type, v).asAddress();
    }

    /** CHANGE-REQUEST */
    public boolean isChangeIp() {
        return value.length == 4 && (value[3] & CHANGE_IP_FLAG) != 0;
    }

    /** CHANGE-REQUEST */
    public boolean isChangePort() {
        return value.length == 4 && (value[3] & CHANGE_PORT_FLAG) != 0;
    }

    /** ERROR-CODE */
    public int asErrorCode() throws StunException {
        if (value.length < 4) {
            throw new StunException("too short ERROR-CODE");
        }
        return (value[2] & 0x07) * 100 + (value[3] & 0xff);
    }

    /** ERROR-CODE */
    public String asErrorReason() {
        return value.length < 4 ? "" : new String(value, 4, value.length - 4, StandardCharsets.UTF_8);
    }

    /** UNKNOWN-ATTRIBUTES */
    public int[] asUnknownAttributes() {
        int[] types = new int[value.length / 2];
        for (int i = 0; i < types.length; i++) {
            types[i] = ((value[i * 2] & 0xff) << 8) | (value[i * 2 + 1] & 0xff);
        }
        return types;
    }

    /** RESPONSE-PORT */
    public int asPort() throws StunException {
        if (value.length != 4) {
            throw new StunException("illegal RESPONSE-PORT length: " + value.length);
        }
        return ((value[0] & 0xff) << 8) | (value[1] & 0xff);
    }

    /** for SOFTWARE, USERNAME, REALM, NONCE */
    public String asString() {
        return new String(value, StandardCharsets.UTF_8);
    }

    /** port is xor-ed with the most significant 16 bits of the magic cookie, address with cookie + transaction id */
    private static void xor(byte[] v, byte[] transactionId) {
        byte[] mask = ByteBuffer.allocate(16).putInt(StunMessage.MAGIC_COOKIE).put(transactionId).array();
        v[2] ^= mask[0];
        v[3] ^= mask[1];
        for (int i = 4; i < v.length; i++) {
            v[i] ^= mask[i - 4];
        }
    }

    /** @return the attribute name */
    public static String nameOf(int type) {
        return switch (type) {
            case MAPPED_ADDRESS -> "MAPPED-ADDRESS";
            case RESPONSE_ADDRESS -> "RESPONSE-ADDRESS";
            case CHANGE_REQUEST -> "CHANGE-REQUEST";
            case SOURCE_ADDRESS -> "SOURCE-ADDRESS";
            case CHANGED_ADDRESS -> "CHANGED-ADDRESS";
            case USERNAME -> "USERNAME";
            case MESSAGE_INTEGRITY -> "MESSAGE-INTEGRITY";
            case ERROR_CODE -> "ERROR-CODE";
            case UNKNOWN_ATTRIBUTES -> "UNKNOWN-ATTRIBUTES";
            case REALM -> "REALM";
            case NONCE -> "NONCE";
            case XOR_MAPPED_ADDRESS -> "XOR-MAPPED-ADDRESS";
            case PADDING -> "PADDING";
            case RESPONSE_PORT -> "RESPONSE-PORT";
            case SOFTWARE -> "SOFTWARE";
            case ALTERNATE_SERVER -> "ALTERNATE-SERVER";
            case FINGERPRINT -> "FINGERPRINT";
            case RESPONSE_ORIGIN -> "RESPONSE-ORIGIN";
            case OTHER_ADDRESS -> "OTHER-ADDRESS";
            default -> String.format("0x%04x", type);
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StunAttribute a && type == a.type && Arrays.equals(value, a.value);
    }

    @Override
    public int hashCode() {
        return type * 31 + Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return nameOf(type) + "[" + HexFormat.of().formatHex(value) + "]";
    }
}
