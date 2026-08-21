/*
 */

import java.net.DatagramPacket;
import java.net.DatagramSocket;

import vavi.util.Debug;


/**
 * @author suno
 * Created on 2003/07/25
 */
public class LatencyCheckerServer extends Thread {
    /** */
    private final DatagramSocket socket;

    /** */
    private final DatagramPacket packet;

    /** */
    public LatencyCheckerServer() throws Exception {
        socket = new DatagramSocket(20001);
        byte[] someBuffer = new byte[1];
        packet = new DatagramPacket(someBuffer, someBuffer.length);
    }

    /** */
    @Override
    public void run() {
        while (true) {
            try {
                socket.receive(packet);
                System.out.println("reply");
                socket.send(packet);
            } catch (Exception e) {
                Debug.printStackTrace(e);
            }
        }
    }

    /** */
    static void main(String[] args) throws Exception {
        new LatencyCheckerServer().start();
    }
}
