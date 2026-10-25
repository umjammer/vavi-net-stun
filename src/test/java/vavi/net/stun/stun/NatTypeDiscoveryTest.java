/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.stun;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import vavi.net.stun.stun.NatDatagramSocket.Filtering;
import vavi.net.stun.stun.NatDatagramSocket.Mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/**
 * NAT type discovery against simulated NATs and firewalls.
 * <p>
 * The server uses 127.0.0.1 and 127.0.0.2 as primary/alternate ip,
 * which are available on Linux by default (on macOS, {@code sudo ifconfig lo0 alias 127.0.0.2}).
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 */
class NatTypeDiscoveryTest {

    static InetAddress primaryIp;
    static InetAddress alternateIp;

    @BeforeAll
    static void setup() throws IOException {
        primaryIp = InetAddress.getByName("127.0.0.1");
        alternateIp = InetAddress.getByName("127.0.0.2");
        boolean available;
        try (DatagramSocket s = new DatagramSocket(new InetSocketAddress(alternateIp, 0))) {
            available = true;
        } catch (IOException e) {
            available = false;
        }
        assumeTrue(available, "127.0.0.2 is not available");
    }

    StunServer server;
    StunServer other;
    DatagramSocket socket;

    @AfterEach
    void teardown() {
        if (socket != null) socket.close();
        if (server != null) server.close();
        if (other != null) other.close();
    }

    /** short retransmission for tests, gives up in 140ms */
    static StunClient client(DatagramSocket socket) {
        return new StunClient(socket).setRetransmission(Duration.ofMillis(20), 3, 4);
    }

    @ParameterizedTest
    @CsvSource({
        "NONE,                       ENDPOINT_INDEPENDENT,       OPEN_INTERNET",
        "NONE,                       ADDRESS_DEPENDENT,          SYMMETRIC_FIREWALL",
        "NONE,                       ADDRESS_AND_PORT_DEPENDENT, SYMMETRIC_FIREWALL",
        "NONE,                       BLOCK_ALL,                  UDP_BLOCKED",
        "ENDPOINT_INDEPENDENT,       ENDPOINT_INDEPENDENT,       FULL_CONE",
        "ENDPOINT_INDEPENDENT,       ADDRESS_DEPENDENT,          RESTRICTED_CONE",
        "ENDPOINT_INDEPENDENT,       ADDRESS_AND_PORT_DEPENDENT, PORT_RESTRICTED_CONE",
        "ENDPOINT_INDEPENDENT,       BLOCK_ALL,                  UDP_BLOCKED",
        "ADDRESS_DEPENDENT,          ADDRESS_DEPENDENT,          SYMMETRIC",
        "ADDRESS_AND_PORT_DEPENDENT, ADDRESS_DEPENDENT,          SYMMETRIC",
        "ADDRESS_AND_PORT_DEPENDENT, ADDRESS_AND_PORT_DEPENDENT, SYMMETRIC",
    })
    void discover(Mapping mapping, Filtering filtering, NatType expected) throws Exception {
        server = new StunServer(new InetSocketAddress(primaryIp, 0), 0, alternateIp).start();
        socket = new NatDatagramSocket(mapping, filtering, primaryIp);

        NatType type = client(socket).discoverNatType(server.getPrimaryAddress());

        assertEquals(expected, type);
    }

    @ParameterizedTest
    @CsvSource({
        "NONE,                       ENDPOINT_INDEPENDENT,       OPEN_INTERNET",
        "NONE,                       ADDRESS_AND_PORT_DEPENDENT, SYMMETRIC_FIREWALL",
        "ENDPOINT_INDEPENDENT,       ENDPOINT_INDEPENDENT,       FULL_CONE",
        "ENDPOINT_INDEPENDENT,       ADDRESS_DEPENDENT,          RESTRICTED_CONE",
        "ENDPOINT_INDEPENDENT,       ADDRESS_AND_PORT_DEPENDENT, PORT_RESTRICTED_CONE",
        "ADDRESS_AND_PORT_DEPENDENT, ADDRESS_AND_PORT_DEPENDENT, SYMMETRIC",
    })
    void discoverWithRelay(Mapping mapping, Filtering filtering, NatType expected) throws Exception {
        // two single ip servers like the overlay network nodes, "change ip" is relayed to the other
        other = new StunServer(new InetSocketAddress(alternateIp, 0), 0, null).start();
        server = new StunServer(new InetSocketAddress(primaryIp, 0), 0, null)
                .setChangeIpRelay((client, response, changePort) -> {
                    other.sendRelayed(client, response, changePort);
                    return true;
                })
                .start();
        socket = new NatDatagramSocket(mapping, filtering, primaryIp);

        NatType type = client(socket).discoverNatType(server.getPrimaryAddress(), server.getAlternatePortAddress());

        assertEquals(expected, type);
    }

    @Test
    void noChangeIpSupport() throws Exception {
        // a server which can not change ip answers 420, a full cone can not be distinguished from a restricted cone
        server = new StunServer(new InetSocketAddress(primaryIp, 0), 0, null).start();
        socket = new NatDatagramSocket(Mapping.ENDPOINT_INDEPENDENT, Filtering.ENDPOINT_INDEPENDENT, primaryIp);

        NatType type = client(socket).discoverNatType(server.getPrimaryAddress(), server.getAlternatePortAddress());

        assertEquals(NatType.RESTRICTED_CONE, type);
    }

    @Test
    void noServer() throws Exception {
        socket = new DatagramSocket(new InetSocketAddress(primaryIp, 0));
        int port;
        try (DatagramSocket unused = new DatagramSocket(new InetSocketAddress(primaryIp, 0))) {
            port = unused.getLocalPort();
        }

        NatType type = client(socket).discoverNatType(new InetSocketAddress(primaryIp, port));

        assertEquals(NatType.UDP_BLOCKED, type);
    }

    @Test
    void realSocketOnOpenInternet() throws Exception {
        server = new StunServer(new InetSocketAddress(primaryIp, 0), 0, alternateIp).start();
        socket = new DatagramSocket(new InetSocketAddress(primaryIp, 0));

        assertEquals(NatType.OPEN_INTERNET, client(socket).discoverNatType(server.getPrimaryAddress()));
    }

    @Test
    void mappedAddressIsStable() throws Exception {
        // the mapped address of a cone NAT can be used for hole punching with any peer
        server = new StunServer(new InetSocketAddress(primaryIp, 0), 0, alternateIp).start();
        NatDatagramSocket nat = new NatDatagramSocket(Mapping.ENDPOINT_INDEPENDENT, Filtering.ADDRESS_AND_PORT_DEPENDENT, primaryIp);
        socket = nat;
        StunClient client = client(socket);

        BindingResponse r1 = client.bind(server.getPrimaryAddress());
        BindingResponse r2 = client.bind(server.getOtherAddress());

        assertEquals(r1.mappedAddress(), r2.mappedAddress());
        assertNotEquals(socket.getLocalSocketAddress(), r1.mappedAddress());
        assertEquals(1, nat.getBindingCount());
    }
}
