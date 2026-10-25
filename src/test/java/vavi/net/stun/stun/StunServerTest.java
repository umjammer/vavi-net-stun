/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.stun;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * StunServerTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 */
class StunServerTest {

    static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    StunServer server;
    DatagramSocket socket;

    @BeforeEach
    void setup() throws Exception {
        server = new StunServer(new InetSocketAddress(LOOPBACK, 0), 0, null).start();
        socket = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
        socket.setSoTimeout(1000);
    }

    @AfterEach
    void teardown() {
        socket.close();
        server.close();
    }

    StunClient client() {
        return new StunClient(socket).setRetransmission(Duration.ofMillis(50), 3, 4);
    }

    /** sends raw bytes, receives a response */
    StunMessage transact(byte[] request) throws Exception {
        socket.send(new DatagramPacket(request, request.length, server.getPrimaryAddress()));
        byte[] buffer = new byte[1500];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        return StunMessage.decode(packet.getData(), 0, packet.getLength());
    }

    @Test
    void binding() throws Exception {
        BindingResponse r = client().bind(server.getPrimaryAddress());
        assertEquals(socket.getLocalSocketAddress(), r.mappedAddress());
        assertEquals(server.getPrimaryAddress(), r.source());
        assertEquals(server.getPrimaryAddress(), r.responseOrigin());
        assertNull(r.otherAddress()); // no alternate ip
        StunMessage m = r.message();
        assertEquals(socket.getLocalSocketAddress(), m.getAddress(StunAttribute.MAPPED_ADDRESS));
        assertNotNull(m.getAttribute(StunAttribute.XOR_MAPPED_ADDRESS));
        assertEquals("vavi-net-stun", m.getAttribute(StunAttribute.SOFTWARE).asString());
        assertTrue(m.hasAttribute(StunAttribute.FINGERPRINT));
    }

    @Test
    void changePort() throws Exception {
        BindingResponse r = client().bind(server.getPrimaryAddress(), false, true);
        assertEquals(server.getAlternatePortAddress(), r.source());
        assertEquals(server.getAlternatePortAddress(), r.responseOrigin());
    }

    @Test
    void changeIpNotSupported() throws Exception {
        StunException e = assertThrows(StunException.class, () -> client().bind(server.getPrimaryAddress(), true, false));
        assertEquals(420, e.getErrorCode());
    }

    @Test
    void unknownComprehensionRequiredAttribute() throws Exception {
        StunMessage request = StunMessage.request(StunMessage.BINDING,
                new StunAttribute(0x7777, new byte[] {1, 2, 3}),
                new StunAttribute(0xc001, new byte[4])); // comprehension-optional is ignored
        StunMessage response = transact(request.encode());
        assertTrue(response.isSameTransaction(request));
        assertEquals(StunMessage.MessageClass.ERROR_RESPONSE, response.getMessageClass());
        assertEquals(420, response.getAttribute(StunAttribute.ERROR_CODE).asErrorCode());
        assertArrayEquals(new int[] {0x7777}, response.getAttribute(StunAttribute.UNKNOWN_ATTRIBUTES).asUnknownAttributes());
    }

    @Test
    void unknownMethod() throws Exception {
        StunMessage request = StunMessage.request(0x123);
        StunMessage response = transact(request.encode());
        assertEquals(StunMessage.MessageClass.ERROR_RESPONSE, response.getMessageClass());
        assertEquals(0x123, response.getMethod());
        assertEquals(400, response.getAttribute(StunAttribute.ERROR_CODE).asErrorCode());
    }

    @Test
    void ignoresGarbageAndIndications() throws Exception {
        byte[] garbage = "give me my ip".getBytes();
        socket.send(new DatagramPacket(garbage, garbage.length, server.getPrimaryAddress()));
        byte[] indication = new StunMessage(StunMessage.MessageClass.INDICATION, StunMessage.BINDING, new byte[12], List.of()).encode();
        socket.send(new DatagramPacket(indication, indication.length, server.getPrimaryAddress()));
        socket.setSoTimeout(300);
        assertThrows(SocketTimeoutException.class, () -> socket.receive(new DatagramPacket(new byte[1500], 1500)));
        // still alive
        assertNotNull(client().bind(server.getPrimaryAddress()));
    }

    @Test
    void legacyRfc3489Request() throws Exception {
        byte[] tid = StunMessageTest.hex("0102030405060708090a0b0c0d0e0f10");
        StunMessage request = new StunMessage(StunMessage.MessageClass.REQUEST, StunMessage.BINDING, tid, List.of());
        StunMessage response = transact(request.encode());
        assertTrue(response.isLegacy());
        assertArrayEquals(tid, response.getTransactionId());
        assertEquals(socket.getLocalSocketAddress(), response.getAddress(StunAttribute.MAPPED_ADDRESS));
        assertEquals(server.getPrimaryAddress(), response.getAddress(StunAttribute.SOURCE_ADDRESS));
        assertFalse(response.hasAttribute(StunAttribute.XOR_MAPPED_ADDRESS));
        assertFalse(response.hasAttribute(StunAttribute.FINGERPRINT));
    }

    @Test
    void responsePort() throws Exception {
        try (DatagramSocket another = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            another.setSoTimeout(1000);
            StunMessage request = StunMessage.request(StunMessage.BINDING, StunAttribute.responsePort(another.getLocalPort()));
            byte[] data = request.encode();
            socket.send(new DatagramPacket(data, data.length, server.getPrimaryAddress()));
            DatagramPacket packet = new DatagramPacket(new byte[1500], 1500);
            another.receive(packet);
            StunMessage response = StunMessage.decode(packet.getData(), 0, packet.getLength());
            assertTrue(response.isSameTransaction(request));
            assertEquals(socket.getLocalSocketAddress(), response.getMappedAddress());
        }
    }

    @Test
    void shortTermCredential() throws Exception {
        server.setCredential("alice", "secret");

        BindingResponse r = client().setCredential("alice", "secret").bind(server.getPrimaryAddress());
        assertTrue(r.message().checkIntegrity(StunMessage.shortTermKey("secret")));

        StunException e = assertThrows(StunException.class, () -> client().setCredential("alice", "wrong").bind(server.getPrimaryAddress()));
        assertEquals(401, e.getErrorCode());

        e = assertThrows(StunException.class, () -> client().bind(server.getPrimaryAddress()));
        assertEquals(400, e.getErrorCode());
    }

    @Test
    void timeout() throws Exception {
        InetSocketAddress nowhere;
        try (DatagramSocket unused = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            nowhere = (InetSocketAddress) unused.getLocalSocketAddress();
        }
        socket.setSoTimeout(1234);
        long t = System.currentTimeMillis();
        assertThrows(SocketTimeoutException.class, () -> client().bind(nowhere));
        long elapsed = System.currentTimeMillis() - t;
        // 50 + 100 + 50 * 4
        assertTrue(elapsed >= 340 && elapsed < 2000, "elapsed: " + elapsed);
        assertEquals(1234, socket.getSoTimeout()); // restored
    }

    @Test
    void ignoresStaleResponses() throws Exception {
        // a response of another transaction must not be taken
        StunMessage stale = StunMessage.request(StunMessage.BINDING);
        byte[] data = stale.encode();
        socket.send(new DatagramPacket(data, data.length, server.getPrimaryAddress()));
        Thread.sleep(100); // the stale response is in the socket buffer

        StunClient client = client();
        BindingResponse r = client.bind(server.getPrimaryAddress());
        assertFalse(r.message().isSameTransaction(stale));
    }
}
