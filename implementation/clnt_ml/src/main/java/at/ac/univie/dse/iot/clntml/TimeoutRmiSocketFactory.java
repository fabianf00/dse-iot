package at.ac.univie.dse.iot.clntml;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.server.RMISocketFactory;

/**
 * RMI sockets with a connect timeout. Without it, a call to an unreachable host (e.g. VPN down)
 * blocks for the operating system's TCP timeout, often more than a minute.
 */
final class TimeoutRmiSocketFactory extends RMISocketFactory {

    private static final String RESPONSE_TIMEOUT_PROPERTY = "sun.rmi.transport.tcp.responseTimeout";

    private final int connectTimeoutMs;

    private TimeoutRmiSocketFactory(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /**
     * Installs the factory for all RMI calls of this JVM, and a response timeout unless one is set
     * on the command line. RMI allows this once per JVM; later calls do nothing.
     */
    static synchronized void install(long connectTimeoutMs, long responseTimeoutMs) {
        if (System.getProperty(RESPONSE_TIMEOUT_PROPERTY) == null) {
            System.setProperty(RESPONSE_TIMEOUT_PROPERTY, Long.toString(responseTimeoutMs));
        }
        if (RMISocketFactory.getSocketFactory() == null) {
            try {
                RMISocketFactory.setSocketFactory(new TimeoutRmiSocketFactory((int) connectTimeoutMs));
            } catch (IOException e) {
                // Another factory was installed concurrently; keep it.
            }
        }
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        return socket;
    }

    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
        return new ServerSocket(port);
    }
}
