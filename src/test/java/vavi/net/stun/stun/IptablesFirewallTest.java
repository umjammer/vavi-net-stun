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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;


/**
 * NAT type discovery behind a real firewall/NAT made by iptables on the loopback interface.
 * <p>
 * Requires root (or CAP_NET_ADMIN), so this is disabled by default.
 * <pre>
 * sudo mvn test -Dtest=IptablesFirewallTest -Dstun.iptables=true
 * </pre>
 * Rules are added to own chains and removed after each test.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "stun.iptables", matches = "true")
class IptablesFirewallTest {

    static final String FILTER_CHAIN = "VAVI_STUN_TEST";
    static final String NAT_CHAIN = "VAVI_STUN_TEST_NAT";

    InetAddress primaryIp;
    InetAddress alternateIp;
    StunServer server;
    DatagramSocket socket;
    final List<String[]> cleanups = new ArrayList<>();

    static void iptables(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("iptables", "-w"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException(String.join(" ", command) + ": " + output);
        }
    }

    @BeforeEach
    void setup() throws Exception {
        primaryIp = InetAddress.getByName("127.0.0.1");
        alternateIp = InetAddress.getByName("127.0.0.2");
        server = new StunServer(new InetSocketAddress(primaryIp, 0), 0, alternateIp).start();
        socket = new DatagramSocket(new InetSocketAddress(primaryIp, 0));
    }

    @AfterEach
    void teardown() throws Exception {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try {
                iptables(cleanups.get(i));
            } catch (IOException e) {
                System.err.println(e.getMessage());
            }
        }
        if (socket != null) socket.close();
        if (server != null) server.close();
    }

    /** creates a chain in the table, jumps to it from the parent chain with the match */
    void chain(String table, String parent, String chain, String... match) throws Exception {
        iptables("-t", table, "-N", chain);
        cleanups.add(new String[] {"-t", table, "-X", chain});
        cleanups.add(new String[] {"-t", table, "-F", chain});
        List<String> jump = new ArrayList<>(List.of("-t", table, "-I", parent));
        jump.addAll(List.of(match));
        jump.addAll(List.of("-j", chain));
        iptables(jump.toArray(String[]::new));
        jump.set(2, "-D");
        cleanups.add(jump.toArray(String[]::new));
    }

    /** filter for packets to the client socket */
    void filter(String... rule) throws Exception {
        if (cleanups.isEmpty()) {
            chain("filter", "INPUT", FILTER_CHAIN, "-p", "udp", "-d", "127.0.0.1", "--dport", String.valueOf(socket.getLocalPort()));
        }
        List<String> args = new ArrayList<>(List.of("-A", FILTER_CHAIN));
        args.addAll(List.of(rule));
        iptables(args.toArray(String[]::new));
    }

    NatType discover() throws IOException {
        return new StunClient(socket).setRetransmission(Duration.ofMillis(50), 3, 4).discoverNatType(server.getPrimaryAddress());
    }

    @Test
    void noFirewall() throws Exception {
        assertEquals(NatType.OPEN_INTERNET, discover());
    }

    @Test
    void statefulFirewall() throws Exception {
        filter("-m", "conntrack", "--ctstate", "ESTABLISHED", "-j", "ACCEPT");
        filter("-j", "DROP");
        assertEquals(NatType.SYMMETRIC_FIREWALL, discover());
    }

    @Test
    void addressFilteringFirewall() throws Exception {
        filter("-s", "127.0.0.2", "-j", "DROP");
        assertEquals(NatType.SYMMETRIC_FIREWALL, discover());
    }

    @Test
    void udpBlocked() throws Exception {
        filter("-j", "DROP");
        assertEquals(NatType.UDP_BLOCKED, discover());
    }

    @Test
    void kernelNat() throws Exception {
        // linux SNAT: endpoint independent mapping (fixed port), connection tracking filtering
        int localPort = socket.getLocalPort();
        int publicPort;
        try (DatagramSocket unused = new DatagramSocket(new InetSocketAddress(primaryIp, 0))) {
            publicPort = unused.getLocalPort();
        }
        chain("nat", "POSTROUTING", NAT_CHAIN, "-p", "udp", "-s", "127.0.0.1", "--sport", String.valueOf(localPort));
        iptables("-t", "nat", "-A", NAT_CHAIN, "-p", "udp", "-j", "SNAT", "--to-source", "127.0.0.1:" + publicPort);
        // a nat router drops unsolicited packets to its public port (so their conntrack entries are
        // not confirmed). on loopback, entries are confirmed in POSTROUTING, so drop them in OUTPUT
        chain("filter", "OUTPUT", FILTER_CHAIN, "-p", "udp", "-d", "127.0.0.1", "--dport", String.valueOf(publicPort));
        iptables("-A", FILTER_CHAIN, "-m", "conntrack", "--ctstate", "NEW", "-j", "DROP");

        BindingResponse r = new StunClient(socket).bind(server.getPrimaryAddress());
        assertEquals(new InetSocketAddress(primaryIp, publicPort), r.mappedAddress());
        assertNotEquals(socket.getLocalSocketAddress(), r.mappedAddress());

        assertEquals(NatType.PORT_RESTRICTED_CONE, discover());
    }
}
