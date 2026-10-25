/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.stun;

import java.io.IOException;


/**
 * Thrown when a STUN message is malformed, or when a STUN transaction
 * is answered by an error response.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 */
public class StunException extends IOException {

    /** error code of an error response, 0 for other errors */
    private final int errorCode;

    /** */
    public StunException(String message) {
        super(message);
        this.errorCode = 0;
    }

    /** for an error response */
    public StunException(int errorCode, String reason) {
        super(errorCode + " " + reason);
        this.errorCode = errorCode;
    }

    /** @return error code of an error response (e.g. 420), 0 if this is not caused by an error response */
    public int getErrorCode() {
        return errorCode;
    }
}
