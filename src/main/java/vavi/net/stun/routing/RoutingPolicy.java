/*
 * Copyright (c) 2005 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.routing;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import vavi.net.stun.Node;
import vavi.net.stun.NodeInfo;
import vavi.net.stun.messaging.ID;
import vavi.net.stun.messaging.Message;
import vavi.net.stun.network.Connection;
import vavi.net.stun.network.Server;
import vavi.net.stun.stun.NatType;
import vavi.net.stun.stun.StunServer;
import vavi.net.stun.stunresolver.ConnectionResolver;
import vavi.net.stun.stunresolver.DatagramConnection;
import vavi.net.stun.stunresolver.DatagramConnectionEventHandler;
import vavi.util.StringUtil;


/**
 * RoutingPolicy.
 *
 * @author suno
 * Created on 2003/07/01
 */
public abstract class RoutingPolicy
    implements DatagramConnectionEventHandler, MessageEventHandler {

    private static final Logger logger = System.getLogger(RoutingPolicy.class.getName());

    /** */
    private final Node node;

    /** */
    private final NatType stunType;

    /** */
    private final ConnectionResolver resolver;

    /** */
    public RoutingPolicy(Node node, NatType stunType) {
        this.node = node;
        this.stunType = stunType;
        this.resolver = new ConnectionResolver(this);
    }

    /** */
    public NatType getLocalSTUNType() {
        return stunType;
    }

    /** */
    public ID getLocalID() {
        return node.getID();
    }

    /** */
    public Node getNodeInterface() {
        return node;
    }

    /** */
    public static RoutingPolicy getRoutingPolicy(Node node, NodeInfo[] servers, NatType stunType) {
        if (stunType.isPubliclyReachable()) {
            return new BasicRoutingPolicy(node, servers, stunType);
        } else {
            return new ChildNodeRoutingPolicy(node, servers, stunType);
        }
    }

    /** */
    @Override
    public void onDatagramConnectionEstablished(DatagramConnection connection) {
        node.onDatagramConnectionEstablished(connection);
    }

    /** */
    @Override
    public void onDatagramConnectionFailed(ID id) {
        node.onDatagramConnectionFailed(id);
    }

    /** */
    public abstract ID getServerIDAtRandom();

    /** */
    public abstract void sendMessage(Message message) throws IOException;

    /** */
    public void getDirectConnection(ID id) throws IOException {
        resolver.connectTo(id);
    }

    /** */
    public abstract NodeInfo[] getAllNodeInfo();

    /** */
    public abstract NodeInfo getLocalNodeInfo();
}


/**
 * ChildNodeRouting.
 */
class ChildNodeRoutingPolicy extends RoutingPolicy {

    private static final Logger logger = System.getLogger(ChildNodeRoutingPolicy.class.getName());

    /** */
    private Connection serverConnection;

    /** */
    private final NetworkMessageBridge bridge;

    /** */
    private final NodeInfo[] serverInfos;

    /** */
    private final ConnectionResolver resolver;

    /** */
    public ChildNodeRoutingPolicy(Node node, NodeInfo[] servers, NatType stunType) {
        super(node, stunType);
        this.serverInfos = servers;
        this.bridge = new NetworkMessageBridge(this);
        this.resolver = new ConnectionResolver(this);
        connectToServer(serverInfos);
    }

    /** */
    @Override
    public ID getServerIDAtRandom() {
        throw new UnsupportedOperationException("don't call this method!!");
    }

    /**
     * @throws IllegalStateException If no server nodes exist
     */
    private void connectToServer(NodeInfo[] servers) {
        for (NodeInfo server : servers) {
            try {
                serverConnection = new Connection(bridge, server.getIP(), server.getTCPConnectionPort(), Connection.Type.CHILD);
                serverConnection.setName("ChildNodeRoutingPolicy: " + serverConnection);
                return;
            } catch (IOException e) {
                logger.log(Level.ERROR, server + " is not alive.", e);
            }
        }
        throw new IllegalStateException("couldn't connect to a server node!!!");
    }

    /** */
    @Override
    public void onConnectionEstablished(Connection connection, ID id, NodeInfo nodeInfo) throws IOException {
        if (connection.getConnectionType().equals(Connection.Type.SERVER)) {
            connection.close();
            return;
        }
        connection.start();
    }

    /** */
    @Override
    public void onDisconnect(Connection aConnection) {
        // /implement!!
        // needs to implement recovery method;
    }

    /** */
    @Override
    public void onMessageArrived(Connection connection, Message message) {
logger.log(Level.INFO, "new message!!:\n" + StringUtil.getDump(message.getData()));
        if (message.isControlMessage()) {
            resolver.onControlMessageArrived(message);
        } else if (message.isGeneralMessage()) {
            getNodeInterface().onMessageArrived(message);
        }
    }

    /** */
    @Override
    public void sendMessage(Message message) throws IOException {
//logger.log(Level.INFO, message);
        serverConnection.write(message.toBytes());
    }

    /** */
    @Override
    public NodeInfo[] getAllNodeInfo() {
        return serverInfos;
    }

    /** */
    @Override
    public NodeInfo getLocalNodeInfo() {
        try {
            return new NodeInfo(InetAddress.getLocalHost(), 0, 0, 0, 0);
        } catch (UnknownHostException e) {
            throw (RuntimeException) new IllegalStateException("may not occur").initCause(e);
        }
    }
}


/**
 * This is the routing policy class used by nodes launched with a global address.
 *
 * BasicRoutingPolicy.
 */
class BasicRoutingPolicy extends RoutingPolicy
    implements MessageEventHandler {

    private static final Logger logger = System.getLogger(BasicRoutingPolicy.class.getName());

    /** */
    private final Map<ID, Connection> serverNodes = new HashMap<>();

    /** */
    private final Map<ID, Connection> childNodes = new HashMap<>();

    /** */
    private final NetworkMessageBridge bridge;

    /** */
    private Server localServer;

    /** */
    private final Map<Connection, NodeInfo> serverInfos = new HashMap<>();

    /** */
    private final NodeInfoServer nodeInfoServer;

    /** */
    private final StunServer stunServer;

    /** */
    private final ConnectionResolver resolver;

    /** */
    private static final int DEFAULTPORT = 2000;

    /**
     * @throws IllegalStateException could not bind any port
     */
    public BasicRoutingPolicy(Node node, NodeInfo[] servers, NatType stunType) {
        super(node, stunType);
logger.log(Level.INFO, "test: " + servers.length);
        NodeInfo[] someNodeInfo = servers;
        this.bridge = new NetworkMessageBridge(this);
        try {
            this.localServer = new Server(bridge, DEFAULTPORT);
        } catch (IOException e) {
logger.log(Level.INFO, "could not bind default port(" + DEFAULTPORT + "), trying other port");
            try {
                this.localServer = new Server(bridge, 0);
            } catch (IOException f) {
                throw (RuntimeException) new IllegalStateException("could not bind any port").initCause(f);
            }
        }
        localServer.setName("BasicRoutingPolicy");
        localServer.start();

        this.nodeInfoServer = new NodeInfoServer(this);
        this.stunServer = createStunServer();
        this.resolver = new ConnectionResolver(this);

        // connect to all other servers
        for (int i = 0; i < someNodeInfo.length; i++) {
            try {
                Connection connection = new Connection(bridge, someNodeInfo[i].getIP(), someNodeInfo[i].getTCPConnectionPort(), Connection.Type.SERVER);
                connection.setName("BasicRoutingPolicy[" + i + "]: " + connection);
            } catch (IOException e) {
                logger.log(Level.ERROR, e.getMessage(), e);
            }
        }
        serverInfos.put(null, getLocalNodeInfo());
    }

    /**
     * Creates a stun server on the default ports, or ephemeral ports if they are in use.
     * "change ip" requests are relayed to another server node through the overlay network.
     *
     * @throws IllegalStateException could not bind any port
     */
    private StunServer createStunServer() {
        StunServer server;
        try {
            server = new StunServer(new InetSocketAddress(StunServer.DEFAULT_PORT), StunServer.DEFAULT_ALTERNATE_PORT, null);
        } catch (IOException e) {
logger.log(Level.INFO, "could not bind stun ports(" + StunServer.DEFAULT_PORT + ", " + StunServer.DEFAULT_ALTERNATE_PORT + "), trying other ports");
            try {
                server = new StunServer(new InetSocketAddress(0), 0, null);
            } catch (IOException f) {
                throw new IllegalStateException("could not bind any stun port", f);
            }
        }
        server.setChangeIpRelay((client, response, changePort) -> {
            ID id = getServerIDAtRandom();
            if (id.equals(getLocalID())) {
                return false;
            }
logger.log(Level.DEBUG, "relaying a stun response for " + client + " to " + id);
            sendMessage(new Message(getLocalID(), id, Message.Type.STUN, encodeRelay(client, response, changePort)));
            return true;
        });
        return server.start();
    }

    /**
     * STUN message payload: address length (1), address, port (2), change port (1), stun response
     */
    private static byte[] encodeRelay(InetSocketAddress client, byte[] response, boolean changePort) {
        byte[] address = client.getAddress().getAddress();
        ByteBuffer bb = ByteBuffer.allocate(1 + address.length + 2 + 1 + response.length);
        bb.put((byte) address.length).put(address).putShort((short) client.getPort()).put((byte) (changePort ? 1 : 0)).put(response);
        return bb.array();
    }

    /** sends a stun response relayed by another server node */
    private void handleSTUNMessage(Message message) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(message.getData());
            byte[] address = new byte[bb.get()];
            bb.get(address);
            InetSocketAddress client = new InetSocketAddress(InetAddress.getByAddress(address), bb.getShort() & 0xffff);
            boolean changePort = bb.get() != 0;
            byte[] response = new byte[bb.remaining()];
            bb.get(response);
logger.log(Level.DEBUG, "sending a relayed stun response to " + client);
            stunServer.sendRelayed(client, response, changePort);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.ERROR, e.getMessage(), e);
        }
    }

    /** */
    private static void sendToSelectedHosts(Map<ID, Connection> hosts, byte[] data) {
        for (Connection connection : hosts.values()) {
            try {
logger.log(Level.INFO, "sending a message to a host");
                connection.write(data);
            } catch (IOException e) {
                logger.log(Level.ERROR, e.getMessage(), e);
            }
        }
    }

    /** */
    public void sendToAllServers(byte[] data) {
        sendToSelectedHosts(serverNodes, data);
    }

    /** */
    public void sendToAllChildren(byte[] data) {
        sendToSelectedHosts(childNodes, data);
    }

    /** */
    public void sendToAllHosts(byte[] data) {
        sendToAllServers(data);
        sendToAllChildren(data);
    }

    /** */
    private static void sendToAllHostsExcept(Collection<Connection> connections, Connection exceptConnection, byte[] data) {
        for (Connection connection : connections) {
            if (connection != exceptConnection) {
                try {
                    connection.write(data);
                } catch (IOException e) {
                    logger.log(Level.ERROR, e.getMessage(), e);
                }
            }
        }
    }

    /** */
    public void sendToAllHostsExcept(Connection exceptConnection, byte[] data) {
        sendToAllHostsExcept(serverNodes.values(), exceptConnection, data);
        sendToAllHostsExcept(childNodes.values(), exceptConnection, data);
    }

    /** */
    @Override
    public void onMessageArrived(Connection connection, Message message) throws IOException {
logger.log(Level.INFO, "message arrived!!");

        if (message.getDestination().equals(ID.BROADCASTID)) {
            if (childNodes.containsValue(connection)) {
                // if it's from a child, send it to every one except the source.
logger.log(Level.INFO, "sending to all except...");
                sendToAllHostsExcept(connection, message.toBytes());
            } else if (serverNodes.containsValue(connection)) {
                // if it's from a server, just send it to the children.
                sendToAllChildren(message.toBytes());
            }
        } else if (!message.getDestination().equals(getLocalID())) {
            if (childNodes.containsKey(message.getDestination())) {
                // to one of the children
                childNodes.get(message.getDestination()).write(message.toBytes());
            } else if (childNodes.containsValue(connection)) {
                // from one of the children
                sendToAllServers(message.toBytes());
            }
        }
        if (message.getDestination().equals(getLocalID()) || message.getDestination().equals(ID.BROADCASTID)) {
//logger.log(Level.INFO, "received a broadcast message");
            if (message.isGeneralMessage()) {
                getNodeInterface().onMessageArrived(message);
            } else if (message.isSTUNMessage()) {
logger.log(Level.INFO, "a stun message");
                handleSTUNMessage(message);
            } else if (message.isControlMessage()) {
                resolver.onControlMessageArrived(message);
            }
        }
    }

    /** */
    @Override
    public void onConnectionEstablished(Connection connection, ID id, NodeInfo nodeInfo) {
        if (connection.getConnectionType().equals(Connection.Type.CHILD)) {
logger.log(Level.INFO, "connection established!! child " + id);
            childNodes.put(id, connection);
        } else {
logger.log(Level.INFO, "connection established!! server " + id);
            serverNodes.put(id, connection);
            serverInfos.put(connection, nodeInfo);
        }
        connection.start();
    }

    /** */
    @Override
    public void onDisconnect(Connection connection) {
        if (connection.getConnectionType().equals(Connection.Type.CHILD)) {
            childNodes.entrySet().remove(connection);
        } else {
            serverNodes.entrySet().remove(connection);
            serverInfos.remove(connection);
        }
    }

    /** */
    private static final Random random = new Random();

    /** */
    @Override
    public ID getServerIDAtRandom() {
        if (serverNodes.isEmpty()) {
            return getLocalID();
        }
        int index = random.nextInt(serverNodes.size());
        return (ID) serverNodes.keySet().toArray()[index];
    }

    /** */
    private static void printEntries(Map<ID, Connection> map) {
        for (Object object : map.entrySet()) {
            System.out.println(object);
        }
    }

    /** */
    @Override
    public void sendMessage(Message message) throws IOException {
logger.log(Level.INFO, message.getMessageContents());
        Connection connection = null;
        if (!message.getDestination().equals(ID.BROADCASTID)) {
logger.log(Level.INFO, "check where to send.");
            printEntries(serverNodes);
            if (childNodes.containsKey(message.getDestination())) {
logger.log(Level.INFO, "sending a message to a child");
                connection = childNodes.get(message.getDestination());
                connection.write(message.toBytes());
            } else if (serverNodes.containsKey(message.getDestination())) {
logger.log(Level.INFO, "sending a message to a server");
                connection = serverNodes.get(message.getDestination());
                connection.write(message.toBytes());
// logger.log(Level.INFO, "successful");
            } else {
logger.log(Level.INFO, "sending a message to all servers");
                sendToAllServers(message.toBytes());
            }
        } else {
logger.log(Level.INFO, "sending broadcast message");
            sendToAllServers(message.toBytes());
            sendToAllChildren(message.toBytes());
        }
    }

    /** */
    @Override
    public NodeInfo[] getAllNodeInfo() {
        Collection<NodeInfo> values = serverInfos.values();
        return values.toArray(NodeInfo[]::new);
    }

    /** */
    @Override
    public NodeInfo getLocalNodeInfo() {
        try {
            return new NodeInfo(InetAddress.getLocalHost(), localServer.getLocalPort(), nodeInfoServer.getPort(), stunServer.getPrimaryAddress().getPort(), stunServer.getAlternatePortAddress().getPort());
        } catch (UnknownHostException e) {
            throw (RuntimeException) new IllegalStateException("may not occur").initCause(e);
        }
    }
}
