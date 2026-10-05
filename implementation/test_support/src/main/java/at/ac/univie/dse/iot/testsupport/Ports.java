package at.ac.univie.dse.iot.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Free ports on the loopback interface, so tests running at the same time do not collide.
 * A port is only free at the time of the check; tests bind it right afterwards.
 */
public final class Ports {

    /** All tests bind to the loopback interface. */
    public static final String HOST = "127.0.0.1";

    private static final int RANGE_ATTEMPTS = 50;
    private static final int LOWEST = 40_000;
    private static final int HIGHEST = 60_000;

    private Ports() {
    }

    /** First port of {@code count} consecutive free TCP ports, e.g. for a CF scan range. */
    public static int freeTcpRange(int count) {
        for (int attempt = 0; attempt < RANGE_ATTEMPTS; attempt++) {
            int base = ThreadLocalRandom.current().nextInt(LOWEST, HIGHEST - count);
            if (allTcpFree(base, count)) {
                return base;
            }
        }
        throw new IllegalStateException("no " + count + " consecutive free TCP ports found");
    }

    public static int freeTcpPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static int freeUdpPort() {
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(HOST, 0))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean allTcpFree(int base, int count) {
        for (int port = base; port < base + count; port++) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(HOST, port));
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }
}
