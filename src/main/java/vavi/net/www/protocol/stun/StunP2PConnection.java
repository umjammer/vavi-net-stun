/*
 * Copyright (c) 2005 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.www.protocol.stun;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import vavi.net.stun.stunresolver.DatagramConnection;
import vavi.net.www.protocol.p2p.P2PConnection;


/**
 * StunURLConnection. 
 *
 * @author <a href="mailto:vavivavi@yahoo.co.jp">Naohide Sano</a> (nsano)
 * @version 0.00 051011 nsano initial version <br>
 */
class StunP2PConnection extends P2PConnection {

    /** */
    private final DatagramConnection datagramConnection;

    /** */
    private final StunURLConnection connection;

    /** */
    StunP2PConnection(DatagramConnection datagramConnection, StunURLConnection connection) {
        this.datagramConnection = datagramConnection;
        this.connection = connection;
    }

    /** unicast TODO Wouldn't just the event be enough? */
    @Override
    public InputStream getInputStream() throws IOException {
        StunInputStream stunInputStream = new StunInputStream();
        connection.addP2PConnectionListener(stunInputStream.getConnectionListener());
        return stunInputStream;
    }

    /** unicast */
    @Override
    public OutputStream getOutputStream() throws IOException {
        return new StunUnicastOutputStream(connection.getNode(), datagramConnection.getID());
    }
}
