/*
 * Copyright (c) 2005 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.net.stun.network;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.ServerSocket;


/**
 * @author suno
 * Created on 2003/06/29
 */
public class Server extends Thread {

    private static final Logger logger = System.getLogger(Server.class.getName());

    /** */
    public static int DEFAULTPORT = 22222;

    /** */
    private final ServerSocket serverSocket;

    /** */
    private final NetworkEventHandler handler;

    /** */
    public Server(NetworkEventHandler handler, int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.handler = handler;
    }

    /** */
    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    /** */
    @Override
    public void run() {
logger.log(Level.INFO, "server started: " + getName() + ": " + serverSocket);
logger.log(Level.INFO, "handler: " + handler);
        while (true) {
            try {
                new Connection(handler, serverSocket.accept(), getName() + ": " + serverSocket);
            } catch (Exception e) {
                logger.log(Level.ERROR, e.getMessage(), e);
            }
        }
    }
}
