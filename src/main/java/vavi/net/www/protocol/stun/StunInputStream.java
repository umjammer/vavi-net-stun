/*
 * Copyright (c) 2005 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.www.protocol.stun;

import java.io.IOException;
import java.io.InputStream;

import vavi.net.www.protocol.p2p.P2PConnectionEvent;
import vavi.net.www.protocol.p2p.P2PConnectionListener;
import vavix.util.RingBuffer;


/**
 * StunInputStream. 
 *
 * @author <a href="mailto:vavivavi@yahoo.co.jp">Naohide Sano</a> (nsano)
 * @version 0.00 051123 nsano initial version <br>
 */
class StunInputStream extends InputStream {

    /**
     * Array of size 1, used by the read method to read just 1 byte.
     */
    private final byte[] single = new byte[1];

    /** */
    protected final RingBuffer buffer = new RingBuffer(4096);

    /** */
    @Override
    public synchronized int available() throws IOException {
        return buffer.available();
    }

    /** */
    @Override
    public synchronized int read() throws IOException {
        if (buffer.read(single, 0, 1) == -1) {
            return -1;
        } else {
            return (single[0] & 0xff);
        }
    }

    /** */
    @Override
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        int bytesRead = buffer.read(b, off, len);
        return bytesRead;
    }

    /** */
    @Override
    public synchronized long skip(long n) throws IOException {
        throw new IOException("skip not supported");
    }

    /** */
    @Override
    public synchronized void mark(int readlimit) {
    }
    
    /** */
    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("reset not supported");
    }

    /** */
    @Override
    public boolean markSupported() {
        return false;
    }
    
    /** */
    P2PConnectionListener getConnectionListener() {
        return connectionListener;
    }

    /** */
    private final P2PConnectionListener connectionListener = new P2PConnectionListener() {
        /** */
        @Override
        public synchronized void dataArrived(P2PConnectionEvent event) {
            byte[] data = (byte[]) event.getData();
            buffer.write(data, 0, data.length);
//logger.log(Level.INFO, "data: " + baos.size() + " bytes\n" + StringUtil.getDump(data));
        }
        /** */
        @Override
        public void newNode(P2PConnectionEvent event) {
        }
        /** */
        @Override
        public void connected(P2PConnectionEvent event) {
        }
    };
}
