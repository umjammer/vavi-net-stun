/*
 * Copyright (c) 2005 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.messaging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * @author suno
 * Created on 2003/06/29
 */
public class ID {

    /** */
    private final byte[] idBytes;

    /** */
    public static final ID BROADCASTID = new ID("broadcast".getBytes());

    /** */
    private static final List<ID> ids = new ArrayList<>();

    /** */
    private ID(byte[] bytes) {
//logger.log(Level.TRACE, "id: " + new String(anyBytes));
        this.idBytes = bytes;
    }

    /** */
    public static ID getID(byte[] bytes) {
        return getID(bytes, 0, bytes.length);
    }

    /** */
    public static ID getID(byte[] bytes, int offset, int length) {
        byte[] someBytes = new byte[length];
        System.arraycopy(bytes, offset, someBytes, 0, length);
        for (ID id : ids) {
            if (Arrays.equals(id.idBytes, someBytes)) {
                return id;
            }
        }
        ID id = new ID(someBytes);
        ids.add(id);
        return id;
    }

    /** */
    public byte[] getBytes() {
        return idBytes;
    }

    /** */
    public String toString() {
        return new String(idBytes);
    }

    /** */
    public boolean equals(Object id) {
//logger.log(Level.TRACE, "tet");
        if (!(id instanceof ID)) {
            return false;
        }
        boolean result = Arrays.equals(this.idBytes, ((ID) id).idBytes);
//logger.log(Level.INFO, "ID being compared: " + someResult);
//logger.log(Level.INFO, " " + anObject.toString());
//logger.log(Level.INFO, "* " + this.toString());
        return result;
    }
}
