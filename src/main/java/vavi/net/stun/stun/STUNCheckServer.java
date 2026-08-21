/*
 * Copyright (c) 2005 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.stun;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vavi.net.stun.NodeInfo;
import vavi.net.stun.messaging.ID;
import vavi.net.stun.messaging.Message;
import vavi.net.stun.routing.RoutingPolicy;


/**
 * STUNCheckServer.
 *
 * @author suno
 * Created on 2003/06/25
 */
public class STUNCheckServer {

    private static final Logger logger = System.getLogger(STUNCheckServer.class.getName());

    /** */
    public enum StunType {
        /** */
        GLOBAL(0),
        /** */
        FULLCONE(1),
        /** */
        RESTRICTED(2),
        /** */
        PORTRESTRICTED(3),
        /** */
        SYMMETRIC(4),
        /** */
        ERROR(-1),
        /** */
        HOSTNOTAVAILABLE(-2);
        /** */
        final int type;
        /** */
        StunType(int type) {
            this.type = type;
        }
        /** */
        public int getType() {
            return type;
        }
    }

    /** */
    public enum Command {
        /** */
        CHECKPORT("give me my ip"),
        /** */
        CHANGEIP("give me a different ip"),
        /** */
        CHANGEPORT("giveme a diffferent port"),
        /** */
        CHANGEIPPORT("giveme a different ip and port");
        /** real messagem of command */
        final String message;
        /** */
        Command(String message) {
            this.message = message;
        }
    }

    /** default checker port 1 */
    public static final int PORT1 = 2002;

    /** default checker port 2 */
    public static final int PORT2 = 2003;

    /** */
    private final RoutingPolicy policy;

    /** */
    private final DatagramSocket[] sockets = new DatagramSocket[2];

    /**
     * Lets two STUN checher server start.
     */
    public STUNCheckServer(RoutingPolicy policy) {
        this.policy = policy;
        try {
            sockets[0] = new DatagramSocket(PORT1);
        } catch (IOException e) {
logger.log(Level.INFO, "could not bind stun port1(" + PORT1 + "). trying some other port.");
            try {
                sockets[0] = new DatagramSocket(0);
            } catch (IOException f) {
                throw (RuntimeException) new IllegalStateException("could not bind any port1").initCause(f);
            }
        }
        try {
            sockets[1] = new DatagramSocket(PORT2);
        } catch (IOException e) {
logger.log(Level.INFO, "could not bind stun port2(" + PORT2 + "). trying some other port.");
            try {
                sockets[1] = new DatagramSocket(0);
            } catch (IOException f) {
                throw (RuntimeException) new IllegalStateException("could not bind any port2").initCause(f);
            }
        }
        packetChecker1.execute(new PacketChecker(sockets[0]));
        packetChecker2.execute(new PacketChecker(sockets[1]));
    }

    /** checker 1 */
    private final ExecutorService packetChecker1 = Executors.newSingleThreadExecutor();

    /** checker 2 */
    private final ExecutorService packetChecker2 = Executors.newSingleThreadExecutor();

    /** Checker class */
    private class PacketChecker implements Runnable {
        private final DatagramSocket socket;

        public PacketChecker(DatagramSocket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1024];
logger.log(Level.INFO, "start listening to " + socket.getLocalAddress() + ":" + socket.getLocalPort());
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (true) {
                try {
                    socket.receive(packet);
                    onPacketArrived(socket, packet);
                } catch (Exception e) {
                    logger.log(Level.INFO, e);
                }
            }
        }
    }

    /** */
    private void onPacketArrived(DatagramSocket socket, DatagramPacket packet) throws IOException {
        byte[] data = packet.getData();
        String command = new String(data, 0, packet.getLength());
        String reply = packet.getAddress().getHostAddress() + ":" + packet.getPort();
        if (Command.CHECKPORT.message.equals(command)) {
            byte[] someReplyData = reply.getBytes();
logger.log(Level.INFO, "<STUN:" + socket.getLocalPort() + ">received check port from:" + reply);
            DatagramPacket somePacket = new DatagramPacket(someReplyData, someReplyData.length, packet.getAddress(), packet.getPort());
            for (int i = 0; i < 3; i++) {
                try {
                    socket.send(somePacket);
                    try { Thread.sleep(100); } catch (InterruptedException e) {}
                } catch (IOException e) {
                    logger.log(Level.ERROR, e.getMessage(), e);
                }
            }
        } else if (Command.CHANGEPORT.message.equals(command)) {
            byte[] replyData = reply.getBytes();
            DatagramPacket somePacket = new DatagramPacket(replyData, replyData.length, packet.getAddress(), packet.getPort());
            DatagramSocket someSocket = (socket == this.sockets[0]) ? this.sockets[1] : this.sockets[0];
logger.log(Level.INFO, "<STUN:" + socket.getLocalPort() + ">received change port from:" + reply);
            for (int i = 0; i < 3; i++) {
                try {
                    someSocket.send(somePacket);
                    try { Thread.sleep(100); } catch (InterruptedException e) {}
                } catch (IOException e) {
                    logger.log(Level.ERROR, e.getMessage(), e);
                }
            }
        } else if (Command.CHANGEIP.message.equals(command) || Command.CHANGEIPPORT.message.equals(command)) {
            ID id = policy.getServerIDAtRandom();
logger.log(Level.INFO, "<STUN:" + socket.getLocalPort() + ">received changeip from" + reply + " : forwarding to " + id);
            Message someMessage = new Message(policy.getLocalID(), id, Message.Type.STUN, reply.getBytes());
            policy.sendMessage(someMessage);
        } else {
logger.log(Level.INFO, "unknown command: " + command);
        }
    }

    /** */
    public void handleSTUNMessage(Message message) {
        String destinationString = new String(message.getData());
        StringTokenizer st = new StringTokenizer(destinationString, ":");
        try {
            InetAddress ip = InetAddress.getByName(st.nextToken());
            int port = Integer.parseInt(st.nextToken());
logger.log(Level.INFO, "<STUN>handling stun control message:" + ip.getHostAddress() + ":" + port);
            DatagramPacket somePacket = new DatagramPacket(message.getData(), message.getData().length, ip, port);
            for (int i = 0; i < 3; i++) {
logger.log(Level.INFO, "sending STUN change ip reply");
                this.sockets[0].send(somePacket);
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            }
        } catch (IOException e) {
            logger.log(Level.ERROR, e.getMessage(), e);
        }
    }

    /** */
    public int getPort1() {
        return sockets[0].getLocalPort();
    }

    /** */
    public int getPort2() {
        return sockets[1].getLocalPort();
    }

    // -------------------------------------------------------------
    // from here below is the methods for stun client
    // -------------------------------------------------------------

    /** */
    public static STUNResponse getRouterInetAddr(DatagramSocket socket, String ip, int port) {
        InetAddress address;
        try {
            address = InetAddress.getByName(ip);
        } catch (IOException e) {
            logger.log(Level.ERROR, e.getMessage(), e);
            return null;
        }
        return getRouterInetAddr(socket, address, port);
    }

    /**
     * 
     * @param socket
     * @param ip
     * @param port
     * @return
     */
    public static STUNResponse getRouterInetAddr(DatagramSocket socket, InetAddress ip, int port) {
        return sendSTUNRequest(socket, STUNCheckServer.Command.CHECKPORT, ip, port);
    }

    /**
     * 
     * @param socket
     * @param command
     * @param ip
     * @param port
     * @return
     */
    private static STUNResponse sendSTUNRequest(DatagramSocket socket, STUNCheckServer.Command command, InetAddress ip, int port) {
        byte[] message = command.message.getBytes();
        DatagramPacket packet = new DatagramPacket(message, message.length, ip, port);
        byte[] buffer = new byte[1024];

logger.log(Level.INFO, "sending a stun request to " + ip + ":" + port);

        DatagramPacket receivePacket = new DatagramPacket(buffer, 1024);

        if (!sendAndReceive(socket, packet, receivePacket)) {
            return null;
        }

        String firstResult = new String(receivePacket.getData(), 0, receivePacket.getLength());
        StringTokenizer st = new StringTokenizer(firstResult, ":");
        InetAddress someIP;
        try {
            someIP = InetAddress.getByName(st.nextToken());
        } catch (IOException e) {
logger.log(Level.ERROR, e.getMessage(), e);
            return null;
        }
        int somePort = Integer.parseInt(st.nextToken());
        return new STUNResponse(someIP, somePort, receivePacket.getAddress(), receivePacket.getPort());
    }

    /** */
    public static STUNCheckServer.StunType checkSTUNType(NodeInfo[] servers) {
        DatagramSocket socket;
        try {
            socket = new DatagramSocket(0);
logger.log(Level.INFO, "opening port: " + socket.getLocalPort());
        } catch (IOException e) {
logger.log(Level.ERROR, e.getMessage(), e);
            return STUNCheckServer.StunType.ERROR;
        }

        for (int i = 0; i < servers.length; i++) {
logger.log(Level.INFO, "sending check port[" + i + "]");
            STUNResponse response1 = sendSTUNRequest(socket, STUNCheckServer.Command.CHECKPORT, servers[i].getIP(), servers[i].getSTUNPort1());
            if (response1 == null) {
                continue;
            }
            if (isLocalInterface(response1.getLocalIp())) {
                return STUNCheckServer.StunType.GLOBAL;
            }
logger.log(Level.INFO, "received checkport reply from:" + response1.getRemoteIp() + ":" + response1.getRemotePort());

logger.log(Level.INFO, "sending change port");
            STUNResponse response2 = sendSTUNRequest(socket, STUNCheckServer.Command.CHANGEPORT, servers[i].getIP(), servers[i].getSTUNPort1());
            if (response2 != null) {
logger.log(Level.INFO, "received change port reply from:" + response2.getRemoteIp() + ":" + response2.getRemotePort());
logger.log(Level.INFO, "sending change ip");
                STUNResponse someResponse3 = sendSTUNRequest(socket, STUNCheckServer.Command.CHANGEIP, servers[i].getIP(), servers[i].getSTUNPort1());
                return (someResponse3 != null) ? STUNCheckServer.StunType.FULLCONE : STUNCheckServer.StunType.RESTRICTED;
            } else {
logger.log(Level.INFO, "changeport reply timed out");
logger.log(Level.INFO, "sending check port2");
                STUNResponse response3 = sendSTUNRequest(socket, STUNCheckServer.Command.CHECKPORT, servers[i].getIP(), servers[i].getSTUNPort2());
                if (response3.getLocalIp().equals(response1.getLocalIp())) {
                    return STUNCheckServer.StunType.PORTRESTRICTED;
                } else {
                    return STUNCheckServer.StunType.SYMMETRIC;
                }
            }
        }
        return STUNCheckServer.StunType.ERROR;
    }

    /** */
    private static boolean isLocalInterface(InetAddress address) {
        try {
            InetAddress someAddress = InetAddress.getLocalHost();
//logger.log(Level.INFO, someAddress);
            return address.equals(someAddress);
        } catch (IOException e) {
logger.log(Level.ERROR, e.getMessage(), e);
            return false;
        }
    }

    /** */
    private static boolean sendAndReceive(DatagramSocket socket, DatagramPacket sendPacket, DatagramPacket receivePacket) {
        int i = 0;
        int timeoutLength = 0;
        boolean flag = false;
        try {
            timeoutLength = socket.getSoTimeout();
            socket.setSoTimeout(300);
        } catch (IOException e) {
logger.log(Level.ERROR, e.getMessage(), e);
            return false;
        }

        try {
            for (int j = 0; j < 3; j++) {
                socket.send(sendPacket);
                for (i = 0; i < 3; i++) {
                    try {
                        socket.receive(receivePacket);
                        flag = true;
                    } catch (IOException e) {
logger.log(Level.INFO, "udp packet timed out");
                    }
                }
                if (flag) {
                    break;
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) {}
            }
        } catch (IOException e) {
logger.log(Level.ERROR, e.getMessage(), e);
        }

        try {
            socket.setSoTimeout(timeoutLength);
        } catch (IOException e) {
logger.log(Level.ERROR, e.getMessage(), e);
        }
logger.log(Level.INFO, "flag: " + flag);
        return flag;
    }
}
