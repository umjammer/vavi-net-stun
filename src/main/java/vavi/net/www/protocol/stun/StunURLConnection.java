/*
 * Copyright (c) 2005 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.www.protocol.stun;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URL;

import vavi.net.stun.Node;
import vavi.net.stun.NodeInfo;
import vavi.net.stun.messaging.ID;
import vavi.net.stun.messaging.Message;
import vavi.net.stun.stun.STUNCheckServer.StunType;
import vavi.net.stun.stunresolver.DatagramConnection;
import vavi.net.www.protocol.p2p.P2PConnectionEvent;
import vavi.net.www.protocol.p2p.P2PURLConnection;


/**
 * StunURLConnection. 
 *
 * @author <a href="mailto:vavivavi@yahoo.co.jp">Naohide Sano</a> (nsano)
 * @version 0.00 051011 nsano initial version <br>
 */
public class StunURLConnection extends P2PURLConnection {

    private static final Logger logger = System.getLogger(StunURLConnection.class.getName());

    /** */
    private static final String NOTIFYPRESENCE = "NOTIFYMYPRESENCE";

    /** */
    StunURLConnection(URL url, String id, NodeInfo[] nodeInfos) {
        this(url, id, nodeInfos, null);
    }

    /** */
    StunURLConnection(URL url, String id, NodeInfo[] nodeInfos, StunType type) {
        super(url);
logger.log(Level.INFO, "enter <init>");
        //
        if (type == null) {
            this.node = new MyNode(ID.getID(id.getBytes()), nodeInfos);
        } else {
            this.node = new MyNode(ID.getID(id.getBytes()), nodeInfos, type);
        }

        //
        try {
            node.sendBroadcastMessage(NOTIFYPRESENCE.getBytes());
        } catch (IOException e) {
            logger.log(Level.ERROR, e.getMessage(), e);
        }
logger.log(Level.INFO, "exit <init>");
    }

    /** */
    private class MyNode extends Node {

        /** */
        public MyNode(ID id, NodeInfo[] servers) {
            super(id, servers);
            NodeInfo nodeInfo = getLocalNodeInfo();
logger.log(Level.INFO, "started at:" + nodeInfo.toString());
        }

        /** */
        public MyNode(ID id, NodeInfo[] servers, StunType type) {
            super(id, servers, type);
            NodeInfo nodeInfo = getLocalNodeInfo();
logger.log(Level.INFO, "started at:" + nodeInfo.toString());
        }

        /** */
        @Override
        public void onDatagramConnectionEstablished(DatagramConnection connection) {
            connectionSupport.connected(new P2PConnectionEvent(this, new StunP2PConnection(connection, StunURLConnection.this)));
logger.log(Level.INFO, "connected: " + connection);
        }

        /** */
        @Override
        public void onDatagramConnectionFailed(ID id) {
            connectionSupport.connected(null);
logger.log(Level.INFO, "connection failed: " + id);
        }

        /** */
        @Override
        public void onMessageArrived(Message message) {
            if (!(new String(message.getData()).equals(NOTIFYPRESENCE))) {
                connectionSupport.dataArrived(new P2PConnectionEvent(this, message.getData()));
            } else if (message.getDestination().equals(ID.BROADCASTID)) {
                try {
                    node.sendUnicastMessage(NOTIFYPRESENCE.getBytes(), message.getSource());
                } catch (IOException e) {
                    logger.log(Level.ERROR, e.getMessage(), e);
                }
            }
            connectionSupport.newNode(new P2PConnectionEvent(this, new StunPeer(message.getSource().toString())));
logger.log(Level.INFO, "new node: " + message.getSource());
        }
    }

    /** */
    private final Node node;

    /** */
    Node getNode() {
        return node;
    }

    /** */
    private String id;

    /**
     * special connect method
     * TODO use Peer as an argment & move to super class
     */
    public synchronized void connect(String id) throws IOException {
        this.id = id;
        connect();
        this.id = null;
    }

    /** TODO be unsupported */
    @Override
    public void connect() throws IOException {
logger.log(Level.INFO, "enter connect");
        if (id == null) {
            throw new IllegalStateException("id is not specified");
        }
logger.log(Level.INFO, "id: " + id + ", connection: " + this.hashCode());
        node.connectTo(ID.getID(id.getBytes()));
logger.log(Level.INFO, "exit connect");
    }

    /**
     * multicast
     * TODO move to super class ???
     * @throws UnsupportedOperationException
     */
    @Override
    public InputStream getInputStream() throws IOException {
        throw new UnsupportedOperationException("not suppoerted");
    }

    /** multicast */
    @Override
    public OutputStream getOutputStream() throws IOException {
        return new StunMulticastOutputStream(node);
    }
}
