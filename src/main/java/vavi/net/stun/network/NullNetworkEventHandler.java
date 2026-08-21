/*
 * Copyright (c) 2005 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.network;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import vavi.util.StringUtil;


/**
 * @author suno
 * Created on 2003/07/01
 */
public class NullNetworkEventHandler implements NetworkEventHandler {

    private static final Logger logger = System.getLogger(NullNetworkEventHandler.class.getName());

    /** */
    @Override
    public void onConnectionEstablished(Connection connection) throws IOException {
logger.log(Level.INFO, connection);
    }

    /** */
    @Override
    public void onPacketArrived(Connection connection, byte[] data) throws IOException {
logger.log(Level.INFO, connection + ": " + StringUtil.getDump(data));
    }

    /** */
    @Override
    public void onDisconnect(Connection connection) throws IOException {
logger.log(Level.INFO, connection);
    }
}
