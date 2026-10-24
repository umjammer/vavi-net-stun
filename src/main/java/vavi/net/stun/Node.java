/*
 * Copyright (c) 2005 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import vavi.net.stun.messaging.ID;
import vavi.net.stun.messaging.Message;
import vavi.net.stun.network.Connection;
import vavi.net.stun.routing.RoutingPolicy;
import vavi.net.stun.routing.MessageEventHandler;
import vavi.net.stun.routing.NodeInfoServer;
import vavi.net.stun.stun.STUNCheckServer;
import vavi.net.stun.stunresolver.DatagramConnectionEventHandler;


/**
 * @author suno
 * Created on 2003/06/29
 */
public abstract class Node
    implements MessageEventHandler, DatagramConnectionEventHandler {

    private static final Logger logger = System.getLogger(Node.class.getName());

    /** */
    private final ID id;

    /** */
    private final RoutingPolicy policy;

    /** 
     * Create a Node that will connect to one of the servers.
     * <p>use this constructor for debug purposes</p>
     */
    public Node(ID id, NodeInfo[] servers, STUNCheckServer.StunType stunType) {
        this.id = id;
        NodeInfo[] someServers = new NodeInfo[0];
        for (NodeInfo server : servers) {
            try {
                someServers = NodeInfoServer.getActiveNodeInfo(server);
                break;
            } catch (IOException e) {
                logger.log(Level.ERROR, "server is not alive: " + e);
            }
        }
        this.policy = RoutingPolicy.getRoutingPolicy(this, someServers, stunType);
    }

    /**
     * Create a Node that will connect to one of the servers.
     * @param id this node's id
     * @param servers 
     */
    public Node(ID id, NodeInfo[] servers) {
        this.id = id;
        NodeInfo[] someServers = new NodeInfo[0];
        for (NodeInfo server : servers) {
            try {
                someServers = NodeInfoServer.getActiveNodeInfo(server);
                break;
            } catch (IOException e) {
                logger.log(Level.INFO, "server is not alive: " + e);
            }
        }
        STUNCheckServer.StunType stunType = STUNCheckServer.checkSTUNType(someServers);
logger.log(Level.INFO, "type: " + stunType);
        this.policy = RoutingPolicy.getRoutingPolicy(this, someServers, stunType);
    }

    /**
     * @param data sending data
     * @param destinationId destination id 
     */
    public void sendUnicastMessage(byte[] data, ID destinationId) throws IOException {
        byte[] someData = new byte[data.length];
        System.arraycopy(data, 0, someData, 0, data.length);
        policy.sendMessage(new Message(id, destinationId, Message.Type.GENERAL, someData));
    }

    /**
     * @param data sending data
     */
    public void sendBroadcastMessage(byte[] data) throws IOException {
        byte[] someData = new byte[data.length];
        System.arraycopy(data, 0, someData, 0, data.length);
        policy.sendMessage(new Message(id, ID.BROADCASTID, Message.Type.GENERAL, someData));
    }

    /** */
    public void connectTo(ID id) throws IOException {
        policy.getDirectConnection(id);
    }

    /** */
    public NodeInfo getLocalNodeInfo() {
        return policy.getLocalNodeInfo();
    }

    /** */
    public ID getID() {
        return id;
    }

    /** */
    @Override
    public final void onConnectionEstablished(Connection connection, ID id, NodeInfo nodeInfo) {
    }

    /** */
    @Override
    public final void onDisconnect(Connection connection) {
    }

    /** */
    @Override
    public final void onMessageArrived(Connection connection, Message message) {
        onMessageArrived(message);
    }

    /** */
    public abstract void onMessageArrived(Message message);
}
