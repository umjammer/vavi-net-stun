/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.stun;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Test vectors from RFC 5769.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 */
class StunMessageTest {

    static byte[] hex(String s) {
        return HexFormat.of().parseHex(s.replaceAll("\\s", ""));
    }

    static final byte[] TRANSACTION_ID = hex("b7e7a701 bc34d686 fa87dfae");

    /** RFC 5769 2.1 */
    static final byte[] SAMPLE_REQUEST = hex("""
            00010058 2112a442 b7e7a701 bc34d686 fa87dfae
            80220010 5354554e 20746573 7420636c 69656e74
            00240004 6e0001ff
            80290008 932ff9b1 51263b36
            00060009 6576746a 3a683676 59202020
            00080014 9aeaa70c bfd8cb56 781ef2b5 b2d3f249 c1b571a2
            80280004 e57a3bcf
            """);

    /** RFC 5769 2.2 */
    static final byte[] SAMPLE_IPV4_RESPONSE = hex("""
            0101003c 2112a442 b7e7a701 bc34d686 fa87dfae
            8022000b 74657374 20766563 746f7220
            00200008 0001a147 e112a643
            00080014 2b91f599 fd9e90c3 8c7489f9 2af9ba53 f06be7d7
            80280004 c07d4c96
            """);

    /** RFC 5769 2.3 */
    static final byte[] SAMPLE_IPV6_RESPONSE = hex("""
            01010048 2112a442 b7e7a701 bc34d686 fa87dfae
            8022000b 74657374 20766563 746f7220
            00200014 0002a147 0113a9fa a5d3f179 bc25f4b5 bed2b9d9
            00080014 a382954e 4be67bf1 1784c97c 8292c275 bfe3ed41
            80280004 c8fb0b4c
            """);

    /** RFC 5769 2.4 */
    static final byte[] SAMPLE_LONG_TERM_REQUEST = hex("""
            00010060 2112a442 78ad3433 c6ad72c0 29da412e
            00060012 e3839ee3 8388e383 aae38383 e382afe3 82b90000
            0015001c 662f2f34 39396b39 35346436 4f4c3334 6f4c3946 53547679 36347341
            0014000b 6578616d 706c652e 6f726700
            00080014 f6702465 6dd64a3e 02b8e071 2e85c9a2 8ca89666
            """);

    static final byte[] SHORT_TERM_KEY = StunMessage.shortTermKey("VOkJxbRl1RmTxUk/WvJxBt");

    @Test
    void sampleRequest() throws Exception {
        StunMessage m = StunMessage.decode(SAMPLE_REQUEST);
        assertEquals(StunMessage.MessageClass.REQUEST, m.getMessageClass());
        assertEquals(StunMessage.BINDING, m.getMethod());
        assertFalse(m.isLegacy());
        assertArrayEquals(TRANSACTION_ID, m.getTransactionId());
        assertEquals("STUN test client", m.getAttribute(StunAttribute.SOFTWARE).asString());
        assertEquals("evtj:h6vY", m.getAttribute(StunAttribute.USERNAME).asString());
        assertTrue(m.checkIntegrity(SHORT_TERM_KEY));
        assertFalse(m.checkIntegrity(StunMessage.shortTermKey("wrong")));
    }

    @Test
    void sampleIpv4Response() throws Exception {
        StunMessage m = StunMessage.decode(SAMPLE_IPV4_RESPONSE);
        assertEquals(StunMessage.MessageClass.SUCCESS_RESPONSE, m.getMessageClass());
        assertEquals("test vector", m.getAttribute(StunAttribute.SOFTWARE).asString());
        assertEquals(new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 32853), m.getMappedAddress());
        assertTrue(m.checkIntegrity(SHORT_TERM_KEY));
    }

    @Test
    void sampleIpv6Response() throws Exception {
        StunMessage m = StunMessage.decode(SAMPLE_IPV6_RESPONSE);
        assertEquals(new InetSocketAddress(InetAddress.getByName("2001:db8:1234:5678:11:2233:4455:6677"), 32853), m.getMappedAddress());
        assertTrue(m.checkIntegrity(SHORT_TERM_KEY));
    }

    @Test
    void sampleLongTermRequest() throws Exception {
        StunMessage m = StunMessage.decode(SAMPLE_LONG_TERM_REQUEST);
        String username = m.getAttribute(StunAttribute.USERNAME).asString();
        String realm = m.getAttribute(StunAttribute.REALM).asString();
        assertEquals("マトリックス", username);
        assertEquals("example.org", realm);
        assertEquals("f//499k954d6OL34oL9FSTvy64sA", m.getAttribute(StunAttribute.NONCE).asString());
        // "The<U+00AD>M<U+00AA>trIX" after SASLprep
        assertTrue(m.checkIntegrity(StunMessage.longTermKey(username, realm, "TheMatrIX")));
    }

    @Test
    void encodeSameAsVector() throws Exception {
        // padding of RFC 5769 is 0x20, ours is 0x00, so use the address only response
        StunMessage request = new StunMessage(StunMessage.MessageClass.REQUEST, StunMessage.BINDING, TRANSACTION_ID, java.util.List.of());
        StunMessage response = StunMessage.successResponse(request,
                StunAttribute.xorAddress(StunAttribute.XOR_MAPPED_ADDRESS, new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 32853), TRANSACTION_ID));
        byte[] encoded = response.encode(null, false);
        assertArrayEquals(hex("0101000c 2112a442 b7e7a701 bc34d686 fa87dfae 00200008 0001a147 e112a643"), encoded);
    }

    @Test
    void roundTrip() throws Exception {
        StunMessage request = StunMessage.request(StunMessage.BINDING,
                StunAttribute.changeRequest(true, false),
                StunAttribute.string(StunAttribute.USERNAME, "user"),
                StunAttribute.string(StunAttribute.SOFTWARE, "abcde"));
        byte[] key = StunMessage.shortTermKey("pass");
        byte[] data = request.encode(key, true);
        assertEquals(0, data.length % 4);

        StunMessage decoded = StunMessage.decode(data);
        assertTrue(decoded.isSameTransaction(request));
        assertTrue(decoded.getAttribute(StunAttribute.CHANGE_REQUEST).isChangeIp());
        assertFalse(decoded.getAttribute(StunAttribute.CHANGE_REQUEST).isChangePort());
        assertEquals("abcde", decoded.getAttribute(StunAttribute.SOFTWARE).asString());
        assertTrue(decoded.checkIntegrity(key));
        assertTrue(decoded.hasAttribute(StunAttribute.FINGERPRINT));
    }

    @Test
    void messageType() {
        byte[] tid = new byte[12];
        assertEquals(0x0001, new StunMessage(StunMessage.MessageClass.REQUEST, 1, tid, java.util.List.of()).getType());
        assertEquals(0x0011, new StunMessage(StunMessage.MessageClass.INDICATION, 1, tid, java.util.List.of()).getType());
        assertEquals(0x0101, new StunMessage(StunMessage.MessageClass.SUCCESS_RESPONSE, 1, tid, java.util.List.of()).getType());
        assertEquals(0x0111, new StunMessage(StunMessage.MessageClass.ERROR_RESPONSE, 1, tid, java.util.List.of()).getType());
        // method bits are split by class bits
        assertEquals(0x3eef, new StunMessage(StunMessage.MessageClass.REQUEST, 0xfff, tid, java.util.List.of()).getType());
    }

    @Test
    void errorCode() throws Exception {
        StunMessage request = StunMessage.request(StunMessage.BINDING);
        StunMessage error = StunMessage.decode(StunMessage.errorResponse(request, 420, "Unknown Attribute",
                StunAttribute.unknownAttributes(0x0003, 0x7777)).encode());
        assertEquals(StunMessage.MessageClass.ERROR_RESPONSE, error.getMessageClass());
        assertEquals(420, error.getAttribute(StunAttribute.ERROR_CODE).asErrorCode());
        assertEquals("Unknown Attribute", error.getAttribute(StunAttribute.ERROR_CODE).asErrorReason());
        assertArrayEquals(new int[] {0x0003, 0x7777}, error.getAttribute(StunAttribute.UNKNOWN_ATTRIBUTES).asUnknownAttributes());
    }

    @Test
    void badFingerprint() {
        byte[] data = SAMPLE_IPV4_RESPONSE.clone();
        data[data.length - 1] ^= 1;
        assertThrows(StunException.class, () -> StunMessage.decode(data));
    }

    @Test
    void tamperedIntegrity() throws Exception {
        byte[] data = SAMPLE_REQUEST.clone();
        data[24 + 4] ^= 1; // SOFTWARE value
        // fix fingerprint by re-encoding is not possible, so strip it
        byte[] stripped = java.util.Arrays.copyOf(data, data.length - 8);
        stripped[3] -= 8;
        StunMessage m = StunMessage.decode(stripped);
        assertFalse(m.checkIntegrity(SHORT_TERM_KEY));
    }

    @Test
    void notStun() {
        assertFalse(StunMessage.isStunMessage(new byte[] {(byte) 0x80, 0, 0, 0}, 0, 4));
        assertThrows(StunException.class, () -> StunMessage.decode("give me my ip".getBytes()));
        byte[] odd = SAMPLE_IPV4_RESPONSE.clone();
        odd[3] = 0x3d; // length not multiple of 4
        assertThrows(StunException.class, () -> StunMessage.decode(odd));
    }

    @Test
    void legacy() throws Exception {
        byte[] tid = hex("00112233 44556677 8899aabb ccddeeff");
        StunMessage request = new StunMessage(StunMessage.MessageClass.REQUEST, StunMessage.BINDING, tid, java.util.List.of());
        byte[] data = request.encode();
        assertEquals(20, data.length); // no FINGERPRINT for RFC 3489
        StunMessage decoded = StunMessage.decode(data);
        assertTrue(decoded.isLegacy());
        assertArrayEquals(tid, decoded.getTransactionId());
        assertNull(decoded.getMappedAddress());
    }
}
