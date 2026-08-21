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
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import vavi.net.stun.NodeInfo;
import vavi.net.stun.network.Connection;
import vavi.net.stun.network.NetworkEventHandler;
import vavi.net.stun.network.NullNetworkEventHandler;
import vavi.net.stun.network.Server;


/**
 * @author suno
 * Created on 2003/07/09
 */
public class NodeInfoServer implements NetworkEventHandler {

    private static final Logger logger = System.getLogger(NodeInfoServer.class.getName());

    /** */
    private final RoutingPolicy policy;

    /** */
    private static final String DELIMITER = "\n";

    /** */
    private Server server;

    /** */
    public static final int DEFAULTPORT = 2001;

    /** */
    public NodeInfoServer(RoutingPolicy routingPolicy) {
        this.policy = routingPolicy;
        try {
            this.server = new Server(this, DEFAULTPORT);
        } catch (IOException e) {
logger.log(Level.INFO, "address already used. trying other ports");
            try {
                this.server = new Server(this, 0);
            } catch (IOException f) {
                throw (RuntimeException) new IllegalStateException("could not bind any port").initCause(f);
            }
        }
        server.setName("NodeInfoServer");
        server.start();
    }

    /** */
    public int getPort() {
        return server.getLocalPort();
    }

    /** */
    public static NodeInfo[] getActiveNodeInfo(NodeInfo targetNode) throws IOException {
        return getActiveNodeInfo(targetNode.getIP(), targetNode.getServerListPort());
    }

    /** */
    public static NodeInfo[] getActiveNodeInfo(InetAddress ip, int port) throws IOException {
        Connection connection = new Connection(new NullNetworkEventHandler(), ip, port, Connection.Type.UNKNOWN);
        connection.setName("NodeInfoServer: " + connection);
        byte[] buffer = new byte[4096];
        // don't think about data longer than the buffer.
logger.log(Level.INFO, "requesting");
        int length = connection.read(buffer);
        connection.write(new byte[] { 1 });
        String nodeInfoString = new String(buffer, 0, length);
logger.log(Level.INFO, "received-" + nodeInfoString);
        StringTokenizer st = new StringTokenizer(nodeInfoString, DELIMITER);
        List<NodeInfo> nodeInfos = new ArrayList<>();
        while (st.hasMoreTokens()) {
            nodeInfos.add(NodeInfo.parseNodeInfo(st.nextToken()));
        }
        return nodeInfos.toArray(NodeInfo[]::new);
    }

    /** */
    @Override
    public void onConnectionEstablished(Connection connection) throws IOException {
        NodeInfo[] nodeInfos = policy.getAllNodeInfo();
logger.log(Level.INFO, "node list request arrived");
        String nodeInfo = "";
        for (NodeInfo info : nodeInfos) {
            nodeInfo += info.toString() + DELIMITER;
        }
logger.log(Level.INFO, "sending local node list:" + nodeInfos.length);
        connection.write(nodeInfo.getBytes());
        connection.start();
    }

    @Override
    public void onDisconnect(Connection connection) throws IOException {
        // do nothing;
    }

    /** */
    @Override
    public void onPacketArrived(Connection connection, byte[] data) throws IOException {
        connection.close();
    }
}
