/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.stun;

import java.io.Closeable;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;


/**
 * STUN server (Binding over UDP).
 * <p>
 * The server listens on {primary ip, alternate ip} x {primary port, alternate port}
 * for NAT behavior discovery (CHANGE-REQUEST). When no alternate ip is given,
 * the server listens on the two ports of the primary ip, and "change ip" requests are
 * delegated to a {@link ChangeIpRelay} (another server which has a different ip),
 * or rejected with 420 when no relay is set.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @see "RFC 8489, RFC 5780, RFC 3489"
 */
public class StunServer implements Closeable {

    private static final Logger logger = System.getLogger(StunServer.class.getName());

    /** IANA assigned port */
    public static final int DEFAULT_PORT = 3478;

    /** conventional alternate port */
    public static final int DEFAULT_ALTERNATE_PORT = 3479;

    /** attributes in a Binding request this server understands */
    private static final Set<Integer> KNOWN_ATTRIBUTES = Set.of(
            StunAttribute.CHANGE_REQUEST,
            StunAttribute.RESPONSE_PORT,
            StunAttribute.PADDING,
            StunAttribute.USERNAME,
            StunAttribute.MESSAGE_INTEGRITY,
            StunAttribute.FINGERPRINT);

    /**
     * Sends a response from a server which has a different ip address.
     */
    @FunctionalInterface
    public interface ChangeIpRelay {
        /**
         * @param client destination of the response
         * @param response encoded response
         * @param changePort the other server should send from its alternate port
         * @return false when relaying is not available, then the server responds with 420
         */
        boolean relay(InetSocketAddress client, byte[] response, boolean changePort) throws IOException;
    }

    /** [ip index][port index], ip index 1 is null when no alternate ip */
    private final DatagramSocket[][] sockets = new DatagramSocket[2][2];

    /** */
    private final List<Thread> threads = new ArrayList<>();

    /** */
    private String software = "vavi-net-stun";

    /** */
    private volatile ChangeIpRelay relay;

    /** short-term credential, null if not required */
    private String username;

    /** */
    private byte[] key;

    /** */
    private volatile boolean closed;

    /**
     * listens on the wildcard address, ports {@link #DEFAULT_PORT} and {@link #DEFAULT_ALTERNATE_PORT}.
     */
    public StunServer() throws IOException {
        this(new InetSocketAddress(DEFAULT_PORT), DEFAULT_ALTERNATE_PORT, null);
    }

    /**
     * @param primary primary address, port 0 for an ephemeral port
     * @param alternatePort alternate port, 0 for an ephemeral port
     * @param alternateIp alternate ip address, nullable
     * @throws IOException could not bind
     */
    public StunServer(InetSocketAddress primary, int alternatePort, InetAddress alternateIp) throws IOException {
        try {
            sockets[0][0] = new DatagramSocket(primary);
            sockets[0][1] = new DatagramSocket(new InetSocketAddress(primary.getAddress(), alternatePort));
            if (alternateIp != null) {
                sockets[1][0] = new DatagramSocket(new InetSocketAddress(alternateIp, sockets[0][0].getLocalPort()));
                sockets[1][1] = new DatagramSocket(new InetSocketAddress(alternateIp, sockets[0][1].getLocalPort()));
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** starts receiver threads */
    public StunServer start() {
        for (int i = 0; i < 2; i++) {
            for (int p = 0; p < 2; p++) {
                if (sockets[i][p] != null) {
                    int ip = i, port = p;
                    Thread thread = Thread.ofPlatform().daemon().name("StunServer-" + getAddress(i, p)).start(() -> loop(ip, port));
                    threads.add(thread);
                }
            }
        }
        return this;
    }

    /** sets SOFTWARE value, null for not sending */
    public StunServer setSoftware(String software) {
        this.software = software;
        return this;
    }

    /** sets a relay for "change ip" when this server does not have an alternate ip */
    public StunServer setChangeIpRelay(ChangeIpRelay relay) {
        this.relay = relay;
        return this;
    }

    /** requires the short-term credential */
    public StunServer setCredential(String username, String password) {
        this.username = username;
        this.key = StunMessage.shortTermKey(password);
        return this;
    }

    /** @return primary ip, primary port */
    public InetSocketAddress getPrimaryAddress() {
        return getAddress(0, 0);
    }

    /** @return primary ip, alternate port */
    public InetSocketAddress getAlternatePortAddress() {
        return getAddress(0, 1);
    }

    /** @return alternate ip, alternate port, null when no alternate ip */
    public InetSocketAddress getOtherAddress() {
        return sockets[1][1] != null ? getAddress(1, 1) : null;
    }

    /** */
    private InetSocketAddress getAddress(int ip, int port) {
        return (InetSocketAddress) sockets[ip][port].getLocalSocketAddress();
    }

    /** receiver */
    private void loop(int ip, int port) {
        DatagramSocket socket = sockets[ip][port];
logger.log(Level.DEBUG, "start listening on " + socket.getLocalSocketAddress());
        byte[] buffer = new byte[1500];
        while (!closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                handle(ip, port, packet);
            } catch (IOException e) {
                if (!closed) {
                    logger.log(Level.DEBUG, e.getMessage(), e);
                }
            }
        }
logger.log(Level.DEBUG, "stop listening on " + socket.getLocalSocketAddress());
    }

    /** */
    private void handle(int ip, int port, DatagramPacket packet) throws IOException {
        InetSocketAddress client = (InetSocketAddress) packet.getSocketAddress();
        StunMessage request;
        try {
            request = StunMessage.decode(packet.getData(), packet.getOffset(), packet.getLength());
        } catch (StunException e) {
logger.log(Level.DEBUG, "drop malformed packet from " + client + ": " + e.getMessage());
            return;
        }
logger.log(Level.TRACE, "received " + request + " from " + client + " on " + getAddress(ip, port));
        if (request.getMessageClass() != StunMessage.MessageClass.REQUEST) {
            return; // indications and responses are silently ignored
        }

        StunMessage response = process(request, ip, port, client);
        byte[] responseKey = response.getMessageClass() == StunMessage.MessageClass.SUCCESS_RESPONSE ? key : null;

        // destination, RESPONSE-PORT
        InetSocketAddress destination = client;
        StunAttribute responsePort = request.getAttribute(StunAttribute.RESPONSE_PORT);
        if (responsePort != null && response.getMessageClass() == StunMessage.MessageClass.SUCCESS_RESPONSE) {
            destination = new InetSocketAddress(client.getAddress(), responsePort.asPort());
        }

        // source, CHANGE-REQUEST
        StunAttribute change = request.getAttribute(StunAttribute.CHANGE_REQUEST);
        boolean changeIp = change != null && change.isChangeIp() && response.getMessageClass() == StunMessage.MessageClass.SUCCESS_RESPONSE;
        boolean changePort = change != null && change.isChangePort() && response.getMessageClass() == StunMessage.MessageClass.SUCCESS_RESPONSE;
        int sourceIp = changeIp ? 1 - ip : ip;
        int sourcePort = changePort ? 1 - port : port;

        if (changeIp && sockets[1][0] == null) {
            ChangeIpRelay relay = this.relay;
            byte[] data = response.encode(responseKey, !request.isLegacy());
            if (relay != null && relay.relay(destination, data, changePort)) {
logger.log(Level.DEBUG, "relayed response for " + destination);
                return;
            }
            response = StunMessage.errorResponse(request, 420, "Unknown Attribute",
                    StunAttribute.unknownAttributes(StunAttribute.CHANGE_REQUEST));
            responseKey = null;
            sourceIp = ip;
            sourcePort = port;
            destination = client;
        } else if (response.getMessageClass() == StunMessage.MessageClass.SUCCESS_RESPONSE) {
            addOrigin(response, request, getAddress(sourceIp, sourcePort));
        }

        send(sockets[sourceIp][sourcePort], destination, response.encode(responseKey, !request.isLegacy()));
    }

    /** @return a response without the origin attributes */
    private StunMessage process(StunMessage request, int ip, int port, InetSocketAddress client) throws StunException {
        if (request.getMethod() != StunMessage.BINDING) {
            return StunMessage.errorResponse(request, 400, "Bad Request");
        }

        int[] unknown = request.getAttributes().stream()
                .filter(StunAttribute::isComprehensionRequired)
                .mapToInt(StunAttribute::type)
                .filter(t -> !KNOWN_ATTRIBUTES.contains(t))
                .distinct()
                .toArray();
        if (unknown.length > 0) {
            return StunMessage.errorResponse(request, 420, "Unknown Attribute", StunAttribute.unknownAttributes(unknown));
        }

        if (key != null) {
            StunAttribute user = request.getAttribute(StunAttribute.USERNAME);
            if (user == null || !request.hasAttribute(StunAttribute.MESSAGE_INTEGRITY)) {
                return StunMessage.errorResponse(request, 400, "Bad Request");
            }
            if (!username.equals(user.asString()) || !request.checkIntegrity(key)) {
                return StunMessage.errorResponse(request, 401, "Unauthorized");
            }
        }

        StunAttribute change = request.getAttribute(StunAttribute.CHANGE_REQUEST);
        if (change != null && change.value().length != 4) {
            return StunMessage.errorResponse(request, 400, "Bad Request");
        }
        StunAttribute responsePort = request.getAttribute(StunAttribute.RESPONSE_PORT);
        if (responsePort != null) {
            responsePort.asPort(); // validates
        }

        StunMessage response = StunMessage.successResponse(request);
        if (request.isLegacy()) {
            response.add(StunAttribute.address(StunAttribute.MAPPED_ADDRESS, client));
        } else {
            response.add(StunAttribute.xorAddress(StunAttribute.XOR_MAPPED_ADDRESS, client, request.getTransactionId()));
            // for clients which do not understand XOR-MAPPED-ADDRESS
            response.add(StunAttribute.address(StunAttribute.MAPPED_ADDRESS, client));
        }
        if (sockets[1][0] != null) {
            int type = request.isLegacy() ? StunAttribute.CHANGED_ADDRESS : StunAttribute.OTHER_ADDRESS;
            response.add(StunAttribute.address(type, getAddress(1 - ip, 1 - port)));
        }
        if (software != null && !request.isLegacy()) {
            response.add(StunAttribute.string(StunAttribute.SOFTWARE, software));
        }
        return response;
    }

    /** RESPONSE-ORIGIN, SOURCE-ADDRESS for RFC 3489. not added when bound to the wildcard address */
    private static void addOrigin(StunMessage response, StunMessage request, InetSocketAddress origin) {
        if (origin.getAddress().isAnyLocalAddress()) {
            return;
        }
        int type = request.isLegacy() ? StunAttribute.SOURCE_ADDRESS : StunAttribute.RESPONSE_ORIGIN;
        response.add(StunAttribute.address(type, origin));
    }

    /** */
    private static void send(DatagramSocket socket, SocketAddress destination, byte[] data) throws IOException {
        socket.send(new DatagramPacket(data, data.length, destination));
    }

    /**
     * Sends a response relayed by another server's {@link ChangeIpRelay}.
     *
     * @param changePort send from the alternate port
     */
    public void sendRelayed(InetSocketAddress client, byte[] response, boolean changePort) throws IOException {
        send(sockets[0][changePort ? 1 : 0], client, response);
    }

    @Override
    public void close() {
        closed = true;
        for (DatagramSocket[] row : sockets) {
            for (DatagramSocket socket : row) {
                if (socket != null) {
                    socket.close();
                }
            }
        }
        for (Thread thread : threads) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
