/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.stun;


/**
 * NAT types (classic STUN, RFC 3489 section 5).
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 */
public enum NatType {
    /** has a public address, no NAT, no firewall */
    OPEN_INTERNET(0),
    /** endpoint independent mapping and filtering */
    FULL_CONE(1),
    /** endpoint independent mapping, address dependent filtering */
    RESTRICTED_CONE(2),
    /** endpoint independent mapping, address and port dependent filtering */
    PORT_RESTRICTED_CONE(3),
    /** mapping depends on the destination */
    SYMMETRIC(4),
    /** has a public address, but a firewall filters packets from the outside which were not requested */
    SYMMETRIC_FIREWALL(5),
    /** could not determine */
    ERROR(-1),
    /** no response from any server, UDP is blocked (or all servers are down) */
    UDP_BLOCKED(-2);

    /** wire code used by the overlay network */
    private final int code;

    NatType(int code) {
        this.code = code;
    }

    /** @return wire code */
    public int getCode() {
        return code;
    }

    /** @return true if anyone can send packets to the host without a hole punching */
    public boolean isPubliclyReachable() {
        return this == OPEN_INTERNET;
    }

    /** @throws IllegalArgumentException unknown code */
    public static NatType valueOf(int code) {
        for (NatType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown code: " + code);
    }

    /**
     * Parses a name, also accepts the names of the old version (GLOBAL, FULLCONE, ...).
     *
     * @throws IllegalArgumentException unknown name
     */
    public static NatType parse(String name) {
        return switch (name) {
            case "GLOBAL" -> OPEN_INTERNET;
            case "FULLCONE" -> FULL_CONE;
            case "RESTRICTED" -> RESTRICTED_CONE;
            case "PORTRESTRICTED" -> PORT_RESTRICTED_CONE;
            case "HOSTNOTAVAILABLE" -> UDP_BLOCKED;
            default -> valueOf(name);
        };
    }
}
