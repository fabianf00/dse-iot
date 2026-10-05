package at.ac.univie.dse.iot.testsupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortsTest {

    @Test
    void aFreeRangeCanBeBoundCompletely() throws IOException {
        int from = Ports.freeTcpRange(5);
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            for (int port = from; port < from + 5; port++) {
                sockets.add(new ServerSocket(port, 1, InetAddress.getByName(Ports.HOST)));
            }
        } finally {
            for (ServerSocket socket : sockets) {
                socket.close();
            }
        }
        assertTrue(from >= 40_000 && from + 5 <= 60_000, "range in 40000–60000, was " + from);
    }

    @Test
    void freeSinglePortsCanBeBound() throws IOException {
        try (ServerSocket tcp = new ServerSocket(Ports.freeTcpPort(), 1, InetAddress.getByName(Ports.HOST));
                DatagramSocket udp = new DatagramSocket(new InetSocketAddress(Ports.HOST, Ports.freeUdpPort()))) {
            assertTrue(tcp.isBound() && udp.isBound());
        }
    }
}
