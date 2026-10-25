/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.stun;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;


/**
 * A STUN message.
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |0 0|     STUN Message Type     |         Message Length        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Magic Cookie                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                                                               |
 * |                     Transaction ID (96 bits)                  |
 * |                                                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 * Messages of RFC 3489 (without the magic cookie, 128 bit transaction id)
 * are also handled, see {@link #isLegacy()}.
 * <p>
 * MESSAGE-INTEGRITY and FINGERPRINT are not stored in the attribute list
 * when building a message, they are appended by {@link #encode(byte[], boolean)}.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @see "RFC 8489, RFC 5389, RFC 3489"
 */
public final class StunMessage {

    /** fixed value of RFC 5389 or later */
    public static final int MAGIC_COOKIE = 0x2112a442;

    /** header size */
    public static final int HEADER_LENGTH = 20;

    /** method Binding */
    public static final int BINDING = 0x001;

    /** xor-ed with CRC32 for the FINGERPRINT */
    private static final int FINGERPRINT_XOR = 0x5354554e;

    /** HMAC-SHA1 size */
    private static final int INTEGRITY_LENGTH = 20;

    /** message classes */
    public enum MessageClass {
        REQUEST,
        INDICATION,
        SUCCESS_RESPONSE,
        ERROR_RESPONSE;

        /** C1C0 bits */
        int bits() {
            return ordinal();
        }
    }

    private static final SecureRandom random = new SecureRandom();

    /** */
    private final MessageClass messageClass;

    /** 12 bit */
    private final int method;

    /** 96 bit, or 128 bit for RFC 3489 */
    private final byte[] transactionId;

    /** */
    private final List<StunAttribute> attributes;

    /** encoded bytes of a decoded message, null when created locally */
    private byte[] raw;

    /** offset of MESSAGE-INTEGRITY in {@link #raw}, -1 when absent */
    private int integrityOffset = -1;

    /**
     * @param transactionId 12 bytes (RFC 5389) or 16 bytes (RFC 3489)
     */
    public StunMessage(MessageClass messageClass, int method, byte[] transactionId, List<StunAttribute> attributes) {
        if (method < 0 || method > 0xfff) {
            throw new IllegalArgumentException("method: " + method);
        }
        if (transactionId.length != 12 && transactionId.length != 16) {
            throw new IllegalArgumentException("transaction id length: " + transactionId.length);
        }
        this.messageClass = messageClass;
        this.method = method;
        this.transactionId = transactionId.clone();
        this.attributes = new ArrayList<>(attributes);
    }

    /** creates a new request with a random transaction id */
    public static StunMessage request(int method, StunAttribute... attributes) {
        byte[] transactionId = new byte[12];
        random.nextBytes(transactionId);
        return new StunMessage(MessageClass.REQUEST, method, transactionId, List.of(attributes));
    }

    /** creates a success response for the request */
    public static StunMessage successResponse(StunMessage request, StunAttribute... attributes) {
        return new StunMessage(MessageClass.SUCCESS_RESPONSE, request.method, request.transactionId, List.of(attributes));
    }

    /** creates an error response for the request */
    public static StunMessage errorResponse(StunMessage request, int code, String reason, StunAttribute... attributes) {
        StunMessage response = new StunMessage(MessageClass.ERROR_RESPONSE, request.method, request.transactionId, List.of());
        response.add(StunAttribute.errorCode(code, reason));
        response.attributes.addAll(List.of(attributes));
        return response;
    }

    public MessageClass getMessageClass() {
        return messageClass;
    }

    public int getMethod() {
        return method;
    }

    public byte[] getTransactionId() {
        return transactionId.clone();
    }

    /** @return true if this is RFC 3489 style message (no magic cookie) */
    public boolean isLegacy() {
        return transactionId.length == 16;
    }

    /** @return true if both have the same transaction id */
    public boolean isSameTransaction(StunMessage other) {
        return Arrays.equals(transactionId, other.transactionId);
    }

    /** @return unmodifiable attributes */
    public List<StunAttribute> getAttributes() {
        return Collections.unmodifiableList(attributes);
    }

    /** @return the first attribute of the type, null if not found */
    public StunAttribute getAttribute(int type) {
        for (StunAttribute attribute : attributes) {
            if (attribute.type() == type) {
                return attribute;
            }
        }
        return null;
    }

    /** */
    public boolean hasAttribute(int type) {
        return getAttribute(type) != null;
    }

    /** MESSAGE-INTEGRITY and FINGERPRINT should not be added, use {@link #encode(byte[], boolean)} */
    public StunMessage add(StunAttribute attribute) {
        if (attribute.type() == StunAttribute.MESSAGE_INTEGRITY || attribute.type() == StunAttribute.FINGERPRINT) {
            throw new IllegalArgumentException("use encode(key, fingerprint) for " + attribute);
        }
        attributes.add(attribute);
        raw = null;
        integrityOffset = -1;
        return this;
    }

    /**
     * @return the mapped address from XOR-MAPPED-ADDRESS, or MAPPED-ADDRESS as a fallback. null if neither exists
     */
    public InetSocketAddress getMappedAddress() throws StunException {
        StunAttribute xor = getAttribute(StunAttribute.XOR_MAPPED_ADDRESS);
        if (xor != null && !isLegacy()) {
            return xor.asXorAddress(transactionId);
        }
        StunAttribute plain = getAttribute(StunAttribute.MAPPED_ADDRESS);
        return plain != null ? plain.asAddress() : null;
    }

    /** @return the address of the address type attribute, null if not found */
    public InetSocketAddress getAddress(int type) throws StunException {
        StunAttribute attribute = getAttribute(type);
        return attribute != null ? attribute.asAddress() : null;
    }

    /** @return message type field */
    int getType() {
        int c = messageClass.bits();
        return (method & 0x000f) | ((method & 0x0070) << 1) | ((method & 0x0f80) << 2) |
                ((c & 0x01) << 4) | ((c & 0x02) << 7);
    }

    /** encodes without MESSAGE-INTEGRITY, with FINGERPRINT (except RFC 3489 messages) */
    public byte[] encode() {
        return encode(null, !isLegacy());
    }

    /**
     * @param key HMAC key for MESSAGE-INTEGRITY, null for no MESSAGE-INTEGRITY
     * @param fingerprint whether to append FINGERPRINT
     */
    public byte[] encode(byte[] key, boolean fingerprint) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (StunAttribute attribute : attributes) {
            writeAttribute(body, attribute);
        }
        if (key != null) {
            byte[] header = header(body.size() + 4 + INTEGRITY_LENGTH);
            byte[] hmac = hmacSha1(key, concat(header, body.toByteArray()));
            writeAttribute(body, new StunAttribute(StunAttribute.MESSAGE_INTEGRITY, hmac));
        }
        if (fingerprint) {
            byte[] header = header(body.size() + 8);
            int crc = crc32(concat(header, body.toByteArray()));
            writeAttribute(body, new StunAttribute(StunAttribute.FINGERPRINT, ByteBuffer.allocate(4).putInt(crc).array()));
        }
        return concat(header(body.size()), body.toByteArray());
    }

    /** */
    private byte[] header(int length) {
        ByteBuffer bb = ByteBuffer.allocate(HEADER_LENGTH);
        bb.putShort((short) getType());
        bb.putShort((short) length);
        if (!isLegacy()) {
            bb.putInt(MAGIC_COOKIE);
        }
        bb.put(transactionId);
        return bb.array();
    }

    /** writes TLV and padding */
    private static void writeAttribute(ByteArrayOutputStream os, StunAttribute attribute) {
        byte[] value = attribute.value();
        os.write(attribute.type() >> 8);
        os.write(attribute.type());
        os.write(value.length >> 8);
        os.write(value.length);
        os.writeBytes(value);
        os.writeBytes(new byte[padding(value.length)]);
    }

    /** */
    private static int padding(int length) {
        return (4 - (length & 3)) & 3;
    }

    /**
     * Quick check for demultiplexing STUN from other protocols on the same port.
     *
     * @return true if the data seems to be a STUN message
     */
    public static boolean isStunMessage(byte[] data, int offset, int length) {
        if (length < HEADER_LENGTH || (data[offset] & 0xc0) != 0) {
            return false;
        }
        int messageLength = ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
        return (messageLength & 3) == 0 && HEADER_LENGTH + messageLength <= length;
    }

    /** */
    public static StunMessage decode(byte[] data) throws StunException {
        return decode(data, 0, data.length);
    }

    /**
     * Decodes a message, verifies FINGERPRINT if present.
     * MESSAGE-INTEGRITY is verified by {@link #checkIntegrity(byte[])}.
     *
     * @throws StunException malformed message
     */
    public static StunMessage decode(byte[] data, int offset, int length) throws StunException {
        if (!isStunMessage(data, offset, length)) {
            throw new StunException("not a stun message");
        }
        ByteBuffer bb = ByteBuffer.wrap(data, offset, length);
        int type = bb.getShort() & 0xffff;
        int messageLength = bb.getShort() & 0xffff;
        int cookie = bb.getInt(offset + 4);
        boolean legacy = cookie != MAGIC_COOKIE;
        byte[] transactionId = Arrays.copyOfRange(data, offset + (legacy ? 4 : 8), offset + HEADER_LENGTH);

        int method = (type & 0x000f) | ((type & 0x00e0) >> 1) | ((type & 0x3e00) >> 2);
        int c = ((type >> 4) & 0x01) | ((type >> 7) & 0x02);
        StunMessage message = new StunMessage(MessageClass.values()[c], method, transactionId, List.of());
        message.raw = Arrays.copyOfRange(data, offset, offset + HEADER_LENGTH + messageLength);

        int p = HEADER_LENGTH;
        boolean afterIntegrity = false;
        while (p < message.raw.length) {
            if (p + 4 > message.raw.length) {
                throw new StunException("truncated attribute header at " + p);
            }
            int attributeType = ((message.raw[p] & 0xff) << 8) | (message.raw[p + 1] & 0xff);
            int attributeLength = ((message.raw[p + 2] & 0xff) << 8) | (message.raw[p + 3] & 0xff);
            int next = p + 4 + attributeLength + padding(attributeLength);
            if (p + 4 + attributeLength > message.raw.length) {
                throw new StunException("truncated attribute " + StunAttribute.nameOf(attributeType));
            }
            byte[] value = Arrays.copyOfRange(message.raw, p + 4, p + 4 + attributeLength);

            if (attributeType == StunAttribute.FINGERPRINT) {
                if (attributeLength != 4 || next != message.raw.length) {
                    throw new StunException("FINGERPRINT must be the last attribute");
                }
                byte[] covered = Arrays.copyOf(message.raw, p);
                int expected = crc32(covered);
                if (ByteBuffer.wrap(value).getInt() != expected) {
                    throw new StunException("FINGERPRINT mismatch");
                }
                message.attributes.add(new StunAttribute(attributeType, value));
            } else if (attributeType == StunAttribute.MESSAGE_INTEGRITY) {
                if (attributeLength != INTEGRITY_LENGTH) {
                    throw new StunException("illegal MESSAGE-INTEGRITY length: " + attributeLength);
                }
                message.integrityOffset = p;
                message.attributes.add(new StunAttribute(attributeType, value));
                afterIntegrity = true;
            } else if (!afterIntegrity) {
                // attributes after MESSAGE-INTEGRITY except FINGERPRINT must be ignored
                message.attributes.add(new StunAttribute(attributeType, value));
            }
            p = next;
        }
        return message;
    }

    /**
     * Verifies MESSAGE-INTEGRITY of a decoded message.
     *
     * @param key short-term: password (SASLprep-ed), long-term: {@link #longTermKey(String, String, String)}
     * @return false if MESSAGE-INTEGRITY is absent or mismatches
     */
    public boolean checkIntegrity(byte[] key) {
        if (raw == null || integrityOffset < 0) {
            return false;
        }
        byte[] covered = Arrays.copyOf(raw, integrityOffset);
        int length = integrityOffset + 4 + INTEGRITY_LENGTH - HEADER_LENGTH;
        covered[2] = (byte) (length >> 8);
        covered[3] = (byte) length;
        byte[] expected = hmacSha1(key, covered);
        byte[] actual = Arrays.copyOfRange(raw, integrityOffset + 4, integrityOffset + 4 + INTEGRITY_LENGTH);
        return MessageDigest.isEqual(expected, actual);
    }

    /** @return the key for the short-term credential mechanism */
    public static byte[] shortTermKey(String password) {
        return password.getBytes(StandardCharsets.UTF_8);
    }

    /** @return MD5(username ":" realm ":" password) for the long-term credential mechanism */
    public static byte[] longTermKey(String username, String realm, String password) {
        try {
            return MessageDigest.getInstance("MD5").digest((username + ":" + realm + ":" + password).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** */
    private static byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** */
    private static int crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue() ^ FINGERPRINT_XOR;
    }

    /** */
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @Override
    public String toString() {
        String m = method == BINDING ? "Binding" : String.format("0x%03x", method);
        return m + " " + messageClass + (isLegacy() ? " (RFC3489)" : "") +
                " tid=" + HexFormat.of().formatHex(transactionId) + " " + attributes;
    }
}
