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
import java.net.UnknownHostException;
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
import vavi.net.stun.stun.STUNCheckServer;
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
    private final STUNCheckServer.StunType stunType;

    /** */
    private final ConnectionResolver resolver;

    /** */
    public RoutingPolicy(Node node, STUNCheckServer.StunType stunType) {
        this.node = node;
        this.stunType = stunType;
        this.resolver = new ConnectionResolver(this);
    }

    /** */
    public STUNCheckServer.StunType getLocalSTUNType() {
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
    public static RoutingPolicy getRoutingPolicy(Node node, NodeInfo[] servers, STUNCheckServer.StunType stunType) {
        if (stunType.equals(STUNCheckServer.StunType.GLOBAL)) {
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
    public ChildNodeRoutingPolicy(Node node, NodeInfo[] servers, STUNCheckServer.StunType stunType) {
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
    private final STUNCheckServer stunServer;

    /** */
    private final ConnectionResolver resolver;

    /** */
    private static final int DEFAULTPORT = 2000;

    /**
     * @throws IllegalStateException could not bind any port
     */
    public BasicRoutingPolicy(Node node, NodeInfo[] servers, STUNCheckServer.StunType stunType) {
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
        this.stunServer = new STUNCheckServer(this);
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
                stunServer.handleSTUNMessage(message);
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
            return new NodeInfo(InetAddress.getLocalHost(), localServer.getLocalPort(), nodeInfoServer.getPort(), this.stunServer.getPort1(), this.stunServer.getPort2());
        } catch (UnknownHostException e) {
            throw (RuntimeException) new IllegalStateException("may not occur").initCause(e);
        }
    }
}
