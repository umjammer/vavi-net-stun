/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.stun;

import java.net.InetSocketAddress;


/**
 * Result of a Binding transaction.
 *
 * @param mappedAddress the reflexive transport address, the address the server saw (XOR-MAPPED-ADDRESS)
 * @param source the address the response actually came from
 * @param responseOrigin the address the server says it sent the response from (RESPONSE-ORIGIN), may be null
 * @param otherAddress the alternate address of the server (OTHER-ADDRESS or CHANGED-ADDRESS), may be null
 * @param message the raw response
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 */
public record BindingResponse(InetSocketAddress mappedAddress,
                              InetSocketAddress source,
                              InetSocketAddress responseOrigin,
                              InetSocketAddress otherAddress,
                              StunMessage message) {

    @Override
    public String toString() {
        return "Binding[mapped=" + mappedAddress + ", source=" + source +
                (responseOrigin != null ? ", origin=" + responseOrigin : "") +
                (otherAddress != null ? ", other=" + otherAddress : "") + "]";
    }
}
