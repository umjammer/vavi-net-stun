/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.stun;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;


/**
 * STUN client (Binding over UDP).
 * <p>
 * The client uses the given socket, so the obtained mapped address is
 * the one for the socket, which can be used for a hole punching.
 * </p>
 * <pre>
 * try (DatagramSocket socket = new DatagramSocket()) {
 *     StunClient client = new StunClient(socket);
 *     InetSocketAddress mapped = client.bind(new InetSocketAddress("stun.example.com", 3478)).mappedAddress();
 *     NatType type = client.discoverNatType(new InetSocketAddress("stun.example.com", 3478));
 * }
 * </pre>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @see "RFC 8489 section 6.2.1 (retransmission), RFC 3489 section 10.1 (NAT type discovery)"
 */
public class StunClient {

    private static final Logger logger = System.getLogger(StunClient.class.getName());

    /** */
    private final DatagramSocket socket;

    /** initial RTO, RFC recommends 500ms */
    private Duration initialRto = Duration.ofMillis(500);

    /** Rc, number of requests to send */
    private int maxRequests = 7;

    /** Rm, waits Rm * RTO after the last request */
    private int lastWaitFactor = 16;

    /** short-term credential, null if not used */
    private String username;

    /** */
    private byte[] key;

    /** sent as SOFTWARE, null for not sending */
    private String software;

    /** */
    public StunClient(DatagramSocket socket) {
        this.socket = socket;
    }

    /**
     * Creates a client with short retransmission (gives up in 3.75 seconds) for the overlay network,
     * where all tests of the NAT type discovery should finish within seconds.
     */
    public static StunClient forOverlay(DatagramSocket socket) {
        return new StunClient(socket).setRetransmission(Duration.ofMillis(250), 4, 8);
    }

    /**
     * Sets retransmission parameters. defaults are RFC's (500ms, 7, 16) which take 39.5 seconds before giving up.
     *
     * @param initialRto initial retransmission timeout, doubled after each retransmission
     * @param maxRequests Rc, total number of requests
     * @param lastWaitFactor Rm, the client waits initialRto * Rm after the last request
     */
    public StunClient setRetransmission(Duration initialRto, int maxRequests, int lastWaitFactor) {
        if (initialRto.isNegative() || initialRto.isZero() || maxRequests < 1 || lastWaitFactor < 1) {
            throw new IllegalArgumentException("rto: " + initialRto + ", rc: " + maxRequests + ", rm: " + lastWaitFactor);
        }
        this.initialRto = initialRto;
        this.maxRequests = maxRequests;
        this.lastWaitFactor = lastWaitFactor;
        return this;
    }

    /** uses the short-term credential mechanism */
    public StunClient setCredential(String username, String password) {
        this.username = username;
        this.key = StunMessage.shortTermKey(password);
        return this;
    }

    /** */
    public StunClient setSoftware(String software) {
        this.software = software;
        return this;
    }

    /** sends a Binding request, gets the mapped address */
    public BindingResponse bind(InetSocketAddress server) throws IOException {
        return bind(server, false, false);
    }

    /**
     * Sends a Binding request with CHANGE-REQUEST.
     *
     * @throws SocketTimeoutException no response
     * @throws StunException error response (e.g. 420 when the server does not support CHANGE-REQUEST)
     */
    public BindingResponse bind(InetSocketAddress server, boolean changeIp, boolean changePort) throws IOException {
        StunMessage request = StunMessage.request(StunMessage.BINDING);
        if (changeIp || changePort) {
            request.add(StunAttribute.changeRequest(changeIp, changePort));
        }
        if (software != null) {
            request.add(StunAttribute.string(StunAttribute.SOFTWARE, software));
        }
        if (username != null) {
            request.add(StunAttribute.string(StunAttribute.USERNAME, username));
        }
        byte[] data = request.encode(key, true);
        DatagramPacket packet = new DatagramPacket(data, data.length, server);

        int originalTimeout = socket.getSoTimeout();
        try {
            long rto = initialRto.toMillis();
            for (int i = 0; i < maxRequests; i++) {
logger.log(Level.TRACE, "send " + request + " to " + server + " (" + (i + 1) + "/" + maxRequests + ")");
                socket.send(packet);
                long wait = i == maxRequests - 1 ? initialRto.toMillis() * lastWaitFactor : rto;
                BindingResponse response = receive(request, System.currentTimeMillis() + wait);
                if (response != null) {
                    return response;
                }
                rto *= 2;
            }
            throw new SocketTimeoutException("no stun response from " + server);
        } finally {
            if (!socket.isClosed()) {
                socket.setSoTimeout(originalTimeout);
            }
        }
    }

    /** @return null when timed out */
    private BindingResponse receive(StunMessage request, long deadline) throws IOException {
        byte[] buffer = new byte[1500];
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.setSoTimeout((int) remaining);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                return null;
            }
            StunMessage response;
            try {
                response = StunMessage.decode(packet.getData(), packet.getOffset(), packet.getLength());
            } catch (StunException e) {
logger.log(Level.DEBUG, "ignore non stun packet from " + packet.getSocketAddress() + ": " + e.getMessage());
                continue;
            }
            if (!response.isSameTransaction(request) || response.getMethod() != request.getMethod()) {
logger.log(Level.DEBUG, "ignore unrelated message: " + response);
                continue;
            }
            switch (response.getMessageClass()) {
            case SUCCESS_RESPONSE -> {
                if (key != null && !response.checkIntegrity(key)) {
logger.log(Level.DEBUG, "ignore response with bad MESSAGE-INTEGRITY: " + response);
                    continue;
                }
                InetSocketAddress mapped = response.getMappedAddress();
                if (mapped == null) {
                    throw new StunException("no mapped address in " + response);
                }
                InetSocketAddress other = response.getAddress(StunAttribute.OTHER_ADDRESS);
                if (other == null) {
                    other = response.getAddress(StunAttribute.CHANGED_ADDRESS);
                }
                InetSocketAddress origin = response.getAddress(StunAttribute.RESPONSE_ORIGIN);
                if (origin == null) {
                    origin = response.getAddress(StunAttribute.SOURCE_ADDRESS);
                }
                BindingResponse result = new BindingResponse(mapped, (InetSocketAddress) packet.getSocketAddress(), origin, other, response);
logger.log(Level.DEBUG, result);
                return result;
            }
            case ERROR_RESPONSE -> {
                StunAttribute error = response.getAttribute(StunAttribute.ERROR_CODE);
                if (error == null) {
                    throw new StunException("error response without ERROR-CODE: " + response);
                }
                throw new StunException(error.asErrorCode(), error.asErrorReason());
            }
            default -> {
logger.log(Level.DEBUG, "ignore " + response);
            }
            }
        }
    }

    /** @see #discoverNatType(InetSocketAddress, InetSocketAddress) */
    public NatType discoverNatType(InetSocketAddress server) throws IOException {
        return discoverNatType(server, null);
    }

    /**
     * Classic NAT type discovery.
     * <pre>
     * Test I:   Binding to the server
     * Test II:  Binding with CHANGE-REQUEST(change ip, change port)
     * Test I':  Binding to the server's alternate address
     * Test III: Binding with CHANGE-REQUEST(change port)
     *
     *                      Test I
     *                        |
     *  no response ----------+---------- response
     *  UDP_BLOCKED                         |
     *                     mapped == local? +-----------------------+
     *                         yes |                                | no
     *                          Test II                          Test II
     *                     ok /       \ no                  ok /       \ no
     *          OPEN_INTERNET     SYMMETRIC_FIREWALL     FULL_CONE     Test I'
     *                                                    mapped differs /   \ same
     *                                                         SYMMETRIC    Test III
     *                                                                   ok /      \ no
     *                                                          RESTRICTED_CONE  PORT_RESTRICTED_CONE
     * </pre>
     * Test III is actually done before Test I', because Test I' to the same ip
     * (when the server has no alternate ip) opens a hole for the response of Test III.
     * When the server cannot change its ip address (420), FULL_CONE is reported
     * as RESTRICTED_CONE and OPEN_INTERNET is decided by Test III.
     *
     * @param server the primary address of the server
     * @param alternate the server's address of a different port (or ip and port),
     *                  used for Test I' when the server does not tell OTHER-ADDRESS. nullable
     */
    public NatType discoverNatType(InetSocketAddress server, InetSocketAddress alternate) throws IOException {
        BindingResponse r1;
        try {
            r1 = bind(server);
        } catch (SocketTimeoutException e) {
logger.log(Level.INFO, "Test I: no response from " + server);
            return NatType.UDP_BLOCKED;
        }
logger.log(Level.DEBUG, "Test I: " + r1);
        boolean local = isLocalAddress(r1.mappedAddress());

        Boolean changed = changeTest(server, true, true);
logger.log(Level.DEBUG, "Test II: " + changed);
        if (local) {
            if (changed == null) {
                changed = changeTest(server, false, true);
logger.log(Level.DEBUG, "Test III instead of II: " + changed);
            }
            return Boolean.TRUE.equals(changed) ? NatType.OPEN_INTERNET : NatType.SYMMETRIC_FIREWALL;
        }
        if (Boolean.TRUE.equals(changed)) {
            return NatType.FULL_CONE;
        }

        // Test III before Test I', because Test I' to the same ip opens a hole for the response of Test III
        Boolean portChanged = changeTest(server, false, true);
logger.log(Level.DEBUG, "Test III: " + portChanged);

        InetSocketAddress other = r1.otherAddress() != null ? r1.otherAddress() : alternate;
        if (other != null) {
            try {
                BindingResponse r2 = bind(other);
logger.log(Level.DEBUG, "Test I': " + r2);
                if (!r2.mappedAddress().equals(r1.mappedAddress())) {
                    return NatType.SYMMETRIC;
                }
            } catch (SocketTimeoutException e) {
logger.log(Level.INFO, "Test I': no response from " + other);
                return NatType.ERROR;
            }
        } else {
logger.log(Level.INFO, "no alternate address, could not detect symmetric NAT");
        }

        return Boolean.TRUE.equals(portChanged) ? NatType.RESTRICTED_CONE : NatType.PORT_RESTRICTED_CONE;
    }

    /**
     * @return true: got a response from the changed address, false: no response,
     *         null: the server does not support the change
     */
    private Boolean changeTest(InetSocketAddress server, boolean changeIp, boolean changePort) throws IOException {
        try {
            BindingResponse r = bind(server, changeIp, changePort);
            InetSocketAddress source = r.source();
            if ((changeIp && source.getAddress().equals(server.getAddress())) ||
                (changePort && !changeIp && source.getPort() == server.getPort())) {
logger.log(Level.INFO, "server did not change the address: " + source);
                return null;
            }
            return true;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (StunException e) {
            if (e.getErrorCode() == 420) {
logger.log(Level.INFO, "server does not support CHANGE-REQUEST(ip=" + changeIp + ", port=" + changePort + ")");
                return null;
            }
            throw e;
        }
    }

    /** @return true if the address is the socket's local address */
    private boolean isLocalAddress(InetSocketAddress address) throws SocketException {
        if (address.getPort() != socket.getLocalPort()) {
            return false;
        }
        InetAddress local = socket.getLocalAddress();
        if (local.isAnyLocalAddress()) {
            return NetworkInterface.getByInetAddress(address.getAddress()) != null;
        }
        return local.equals(address.getAddress());
    }
}
