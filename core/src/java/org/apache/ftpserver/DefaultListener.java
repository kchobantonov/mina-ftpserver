package org.apache.ftpserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import org.apache.commons.logging.Log;
import org.apache.ftpserver.interfaces.Connection;
import org.apache.ftpserver.interfaces.ConnectionManager;
import org.apache.ftpserver.interfaces.FtpServerContext;

/**
 * The default {@link Listener} implementation.
 *
 */
public class DefaultListener implements Listener, Runnable {

    private Log log;
    
    private FtpServerContext ftpConfig;

    private ServerSocket serverSocket;

    private Thread listenerThread;

    private boolean suspended = false;

    /**
     * Constructs a listener based on the configuration object
     * 
     * @param ftpConfig Configuration for the listener
     */
    public DefaultListener(FtpServerContext ftpConfig) {
        this.ftpConfig = ftpConfig;
        
        log = ftpConfig.getLogFactory().getInstance(getClass());
    }

    /**
     * @see Listener#start()
     */
    public void start() throws Exception {
        serverSocket = ftpConfig.getSocketFactory().createServerSocket();

        listenerThread = new Thread(this);
        listenerThread.start();

    }

    /**
     * The main thread method for the listener
     */
    public void run() {
        if(serverSocket == null) {
            throw new IllegalStateException("start() must be called before run()");
        }
        
        log.info("Listener started on port " + serverSocket.getLocalPort());

        // ftpConfig might be null if stop has been called
        if (ftpConfig == null) {
            return;
        }

        ConnectionManager conManager = ftpConfig.getConnectionManager();
        
        while (true) {
            try {

                // closed - return
                if (serverSocket == null) {
                    return;
                }

                // accept new connection .. if suspended
                // close immediately.
                Socket soc = serverSocket.accept();

                if (suspended) {
                    try {
                        soc.close();
                    } catch (Exception ex) {
                        // ignore
                    }
                    continue;
                }

                Connection connection = new RequestHandler(ftpConfig, soc);
                conManager.newConnection(connection);
            } catch (Exception ex) {
                return;
            }
        }
    }

    /**
     * @see Listener#stop()
     */
    public synchronized void stop() {
        // close server socket
        if (serverSocket != null) {

            try {
                serverSocket.close();
            } catch (IOException ex) {
            }
            serverSocket = null;
        }

        listenerThread.interrupt();

        // wait for the runner thread to terminate
        if (listenerThread != null && listenerThread.isAlive()) {

            try {
                listenerThread.join();
            } catch (InterruptedException ex) {
            }
            listenerThread = null;
        }
    }

    /**
     * @see Listener#isStopped()
     */
    public boolean isStopped() {
        return listenerThread == null;

    }

    /**
     * @see Listener#isSuspended()
     */
    public boolean isSuspended() {
        return suspended;
    }

    /**
     * @see Listener#resume()
     */
    public void resume() {
        suspended = false;
    }

    /**
     * @see Listener#suspend()
     */
    public void suspend() {
        suspended = true;
    }
}