/*
 * Copyright (c) 2005 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.routing;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import vavi.net.stun.NodeInfo;
import vavi.net.stun.messaging.ID;
import vavi.net.stun.messaging.Message;
import vavi.net.stun.network.Connection;
import vavi.net.stun.network.NetworkEventHandler;


/**
 * @author suno
 * Created on 2003/07/01
 */
public class NetworkMessageBridge implements NetworkEventHandler {

    private static final Logger logger = System.getLogger(NetworkMessageBridge.class.getName());

    /** */
    private final RoutingPolicy handler;

    /** */
    public NetworkMessageBridge(RoutingPolicy handler) {
        this.handler = handler;
    }

    /** */
    @Override
    public void onConnectionEstablished(Connection connection) throws IOException {
        byte[] buffer = new byte[2024];
        NodeInfo nodeInfo;
        ID id;
        try {
            connection.write(this.handler.getLocalID().getBytes());
            int count = connection.read(buffer);
            // sleep for 30 millisecs to make sure tcp packets don't concatenate.
            id = ID.getID(buffer, 0, count);
            try { Thread.sleep(30); } catch (InterruptedException e) {}
            connection.write(handler.getLocalNodeInfo().toString().getBytes());
            count = connection.read(buffer);
            nodeInfo = NodeInfo.parseNodeInfo(new String(buffer, 0, count));
        } catch (IOException e) {
            logger.log(Level.ERROR, e.getMessage(), e);
            connection.close();
            return;
        }
        handler.onConnectionEstablished(connection, id, nodeInfo);
    }

    /** */
    private byte[] buffer;

    /** */
    private int remaining = 0;

    /** */
    @Override
    public void onPacketArrived(Connection connection, byte[] data) throws IOException {
logger.log(Level.INFO, "<<received a packet");
        if (remaining == 0) {
            int someDataLength = ((data[0] & 0xff) << 8) | (data[1] & 0xff) + 2;
logger.log(Level.INFO, "received: " + data.length + " expected: " + someDataLength + " 1:" + data[0] + " 2:" + data[1]);
            if (someDataLength == data.length) {
logger.log(Level.INFO, "<<new message");
                Message someMessage = new Message(data);
logger.log(Level.INFO, someMessage.getSource() + " -> " + someMessage.getDestination());
                handler.onMessageArrived(connection, new Message(data));
                return;
            } else {
                remaining = someDataLength;
                buffer = new byte[someDataLength];
            }
        }

        if (remaining == data.length) {
            // exactly the size expected
            System.arraycopy(data, 0, buffer, buffer.length - remaining, data.length);
logger.log(Level.INFO, "<<new message");
            handler.onMessageArrived(connection, new Message(buffer));
            remaining = 0;
        } else if (remaining > data.length) {
            // not enough
            System.arraycopy(data, 0, buffer, buffer.length - remaining, data.length);
            remaining -= data.length;
        } else if (remaining < data.length) {
            // longer than expected
            System.arraycopy(data, 0, buffer, buffer.length - remaining, remaining);
logger.log(Level.INFO, "<<new message");
            handler.onMessageArrived(connection, new Message(buffer));
            byte[] someBytes = new byte[data.length - remaining];
            System.arraycopy(data, remaining, someBytes, 0, someBytes.length);
            remaining = 0;
            onPacketArrived(connection, someBytes);
        }
    }

    /** */
    @Override
    public void onDisconnect(Connection connection) throws IOException {
        handler.onDisconnect(connection);
    }
}
