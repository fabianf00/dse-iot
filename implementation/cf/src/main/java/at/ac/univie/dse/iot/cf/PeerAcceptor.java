package at.ac.univie.dse.iot.cf;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts incoming TCP connections (unlimited) and hands each one to the handshake pool, so a
 * slow or silent client cannot block further accepts.
 */
final class PeerAcceptor {

    private static final Logger LOG = LoggerFactory.getLogger(PeerAcceptor.class);

    private final ServerSocket serverSocket;
    private final ExecutorService handshakePool;
    private final Consumer<Socket> handler;
    private final Thread thread;

    PeerAcceptor(ServerSocket serverSocket, ExecutorService handshakePool, Consumer<Socket> handler, String threadName) {
        this.serverSocket = serverSocket;
        this.handshakePool = handshakePool;
        this.handler = handler;
        this.thread = new Thread(this::acceptLoop, threadName);
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    void stop() {
        try {
            serverSocket.close();
        } catch (IOException e) {
            // Nothing left to release.
        }
    }

    void awaitStopped(long millis) throws InterruptedException {
        thread.join(millis);
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    LOG.warn("Accepting a connection failed: {}", e.getMessage());
                }
                continue;
            }
            try {
                handshakePool.execute(() -> handler.accept(socket));
            } catch (RejectedExecutionException e) {
                LOG.warn("Too many pending handshakes, rejected connection from {}", socket.getRemoteSocketAddress());
                closeQuietly(socket);
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Nothing left to release.
        }
    }
}
