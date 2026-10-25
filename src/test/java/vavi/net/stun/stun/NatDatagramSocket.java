/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.stun;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


/**
 * A UDP socket behind a simulated NAT/firewall (RFC 4787 behaviors).
 * <p>
 * The application sees a private address ({@link #getLocalAddress()}),
 * outgoing packets are sent from "public" sockets on the loopback interface
 * chosen by the mapping behavior, and incoming packets on them are
 * dropped by the filtering behavior.
 * </p>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 */
class NatDatagramSocket extends DatagramSocket {

    /** how an internal endpoint is mapped to a public endpoint */
    enum Mapping {
        /** no translation, the socket has a public address (firewall only) */
        NONE,
        ENDPOINT_INDEPENDENT,
        ADDRESS_DEPENDENT,
        ADDRESS_AND_PORT_DEPENDENT
    }

    /** which incoming packets are allowed on a mapping */
    enum Filtering {
        ENDPOINT_INDEPENDENT,
        ADDRESS_DEPENDENT,
        ADDRESS_AND_PORT_DEPENDENT,
        /** UDP is blocked */
        BLOCK_ALL
    }

    /** a public side endpoint */
    private class Binding {
        final DatagramSocket socket;
        /** destinations the internal endpoint has sent to through this binding */
        final Set<InetSocketAddress> permissions = ConcurrentHashMap.newKeySet();

        Binding() throws SocketException {
            socket = new DatagramSocket(new InetSocketAddress(publicAddress, 0));
            Thread.ofPlatform().daemon().name("nat-" + socket.getLocalPort()).start(this::loop);
        }

        void loop() {
            byte[] buffer = new byte[1500];
            while (!socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (IOException e) {
                    return;
                }
                InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
                if (allowed(from)) {
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), data, 0, data.length);
                    queue.add(new DatagramPacket(data, data.length, from));
                }
            }
        }

        boolean allowed(InetSocketAddress from) {
            return switch (filtering) {
                case ENDPOINT_INDEPENDENT -> true;
                case ADDRESS_DEPENDENT -> permissions.stream().anyMatch(p -> p.getAddress().equals(from.getAddress()));
                case ADDRESS_AND_PORT_DEPENDENT -> permissions.contains(from);
                case BLOCK_ALL -> false;
            };
        }
    }

    private final Mapping mapping;
    private final Filtering filtering;
    private final InetAddress publicAddress;
    private final InetSocketAddress privateAddress;

    /** key depends on the mapping behavior */
    private final Map<Object, Binding> bindings = new ConcurrentHashMap<>();

    /** packets passed the filter */
    private final BlockingQueue<DatagramPacket> queue = new LinkedBlockingQueue<>();

    private volatile int timeout;

    private volatile boolean closed;

    /**
     * @param publicAddress the "public" ip address of the NAT
     */
    NatDatagramSocket(Mapping mapping, Filtering filtering, InetAddress publicAddress) throws IOException {
        super((SocketAddress) null); // unbound, never used
        this.mapping = mapping;
        this.filtering = filtering;
        this.publicAddress = publicAddress;
        if (mapping == Mapping.NONE) {
            Binding binding = new Binding();
            bindings.put("*", binding);
            this.privateAddress = (InetSocketAddress) binding.socket.getLocalSocketAddress();
        } else {
            this.privateAddress = new InetSocketAddress(InetAddress.getByAddress(new byte[] {(byte) 192, (byte) 168, 0, 2}), 50000);
        }
    }

    /** */
    private Binding binding(InetSocketAddress destination) {
        Object key = switch (mapping) {
            case NONE, ENDPOINT_INDEPENDENT -> "*";
            case ADDRESS_DEPENDENT -> destination.getAddress();
            case ADDRESS_AND_PORT_DEPENDENT -> destination;
        };
        return bindings.computeIfAbsent(key, k -> {
            try {
                return new Binding();
            } catch (SocketException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public void send(DatagramPacket p) throws IOException {
        if (closed) {
            throw new SocketException("closed");
        }
        InetSocketAddress destination = (InetSocketAddress) p.getSocketAddress();
        Binding binding = binding(destination);
        binding.permissions.add(destination);
        binding.socket.send(new DatagramPacket(p.getData(), p.getOffset(), p.getLength(), destination));
    }

    @Override
    public void receive(DatagramPacket p) throws IOException {
        if (closed) {
            throw new SocketException("closed");
        }
        DatagramPacket received;
        try {
            received = timeout == 0 ? queue.take() : queue.poll(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new SocketException("interrupted");
        }
        if (received == null) {
            throw new SocketTimeoutException("timeout");
        }
        int length = Math.min(received.getLength(), p.getData().length - p.getOffset());
        System.arraycopy(received.getData(), 0, p.getData(), p.getOffset(), length);
        p.setLength(length);
        p.setSocketAddress(received.getSocketAddress());
    }

    @Override
    public void setSoTimeout(int timeout) {
        this.timeout = timeout;
    }

    @Override
    public int getSoTimeout() {
        return timeout;
    }

    @Override
    public InetAddress getLocalAddress() {
        return privateAddress.getAddress();
    }

    @Override
    public int getLocalPort() {
        return privateAddress.getPort();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return privateAddress;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /** @return number of public endpoints allocated */
    int getBindingCount() {
        return bindings.size();
    }

    @Override
    public void close() {
        closed = true;
        bindings.values().forEach(b -> b.socket.close());
        super.close();
    }
}
