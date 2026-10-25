[![Release](https://jitpack.io/v/umjammer/vavi-net-stun.svg)](https://jitpack.io/#umjammer/vavi-net-stun)
[![Java CI](https://github.com/umjammer/vavi-net-stun/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-net-stun/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-net-stun/actions/workflows/codeql.yml/badge.svg)](https://github.com/umjammer/vavi-net-stun/actions/workflows/codeql.yml)
![Java](https://img.shields.io/badge/Java-25-b07219)

# vavi-net-stun

STUN for Java

## Install

 * [maven](https://jitpack.io/#umjammer/vavi-net-stun)

## Usage

STUN ([RFC 8489](https://www.rfc-editor.org/rfc/rfc8489) / 5389, Binding over UDP) client, server and
classic NAT type discovery ([RFC 3489](https://www.rfc-editor.org/rfc/rfc3489) algorithm with [RFC 5780](https://www.rfc-editor.org/rfc/rfc5780) attributes).

### client

```java
try (DatagramSocket socket = new DatagramSocket()) {
    StunClient client = new StunClient(socket);
    // the reflexive address of this socket
    InetSocketAddress mapped = client.bind(new InetSocketAddress("stun.example.com", 3478)).mappedAddress();
    // OPEN_INTERNET, FULL_CONE, RESTRICTED_CONE, PORT_RESTRICTED_CONE, SYMMETRIC, SYMMETRIC_FIREWALL, UDP_BLOCKED
    NatType type = client.discoverNatType(new InetSocketAddress("stun.example.com", 3478));
}
```

### server

```java
// primary 192.0.2.1:3478, alternate port 3479, alternate ip 192.0.2.2 (for CHANGE-REQUEST)
StunServer server = new StunServer(new InetSocketAddress("192.0.2.1", 3478), 3479, InetAddress.getByName("192.0.2.2")).start();
```

A server without an alternate ip answers "change ip" with 420, or delegates it to another server
by `StunServer#setChangeIpRelay` (the overlay network nodes relay to each other this way).

### features

 * XOR-MAPPED-ADDRESS, MAPPED-ADDRESS, RESPONSE-ORIGIN, OTHER-ADDRESS, CHANGE-REQUEST, RESPONSE-PORT, SOFTWARE
 * FINGERPRINT, MESSAGE-INTEGRITY (short-term credential, long-term key)
 * RFC 8489 retransmission (RTO 500ms, Rc 7, Rm 16, configurable)
 * error responses 400, 401, 420 with UNKNOWN-ATTRIBUTES
 * RFC 3489 clients (no magic cookie) are answered with MAPPED-ADDRESS, SOURCE-ADDRESS, CHANGED-ADDRESS

### tests

 * `StunMessageTest` ... RFC 5769 test vectors
 * `NatTypeDiscoveryTest` ... discovery against simulated NATs/firewalls (RFC 4787 mapping/filtering behaviors), uses 127.0.0.2
 * `IptablesFirewallTest` ... discovery behind real iptables firewalls and SNAT, needs root
   ```
   sudo mvn test -Dtest=IptablesFirewallTest -Dstun.iptables=true
   ```

## References

- [RFC 8489](https://www.rfc-editor.org/rfc/rfc8489) STUN
- [RFC 5780](https://www.rfc-editor.org/rfc/rfc5780) NAT Behavior Discovery Using STUN
- [RFC 5769](https://www.rfc-editor.org/rfc/rfc5769) Test Vectors for STUN
- [RFC 4787](https://www.rfc-editor.org/rfc/rfc4787) NAT Behavioral Requirements for Unicast UDP
- [original](https://web.archive.org/web/20040610043750/http://www.ht.sfc.keio.ac.jp/~suno/term/2003s/)
- https://github.com/sisbell/jxta (java p2p ri)

## TODO

  - original
    - Name Resolution - Resolves node name collisions.
    - Topology between parent nodes - Full mesh allows for a smarter implementation.
    - Recovery when a child node is disconnected from its parent node - Simply reconnect to a suitable parent node selected from the parent node list.
  - ~~URLConnection-ize~~
  - Connection Are listeners released when the stream closes?
  - Distributed hashing?
