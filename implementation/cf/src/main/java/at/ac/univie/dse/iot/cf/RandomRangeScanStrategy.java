package at.ac.univie.dse.iot.cf;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Picks a uniformly random {@code host:port} from the configured hosts × port range.
 *
 * <p>Small search spaces are enumerated, so a free candidate is always found if one exists.
 * Large ones are sampled a fixed number of times. Hosts that cannot be resolved (e.g. the VPN is
 * down) are skipped for this round.
 */
final class RandomRangeScanStrategy implements ScanStrategy {

    private static final int ENUMERATION_LIMIT = 4096;
    private static final int RANDOM_ATTEMPTS = 64;

    private final List<String> hosts;
    private final int fromPort;
    private final int toPort;
    private final Random random;

    RandomRangeScanStrategy(List<String> hosts, int fromPort, int toPort, Random random) {
        this.hosts = List.copyOf(hosts);
        this.fromPort = fromPort;
        this.toPort = toPort;
        this.random = random;
    }

    @Override
    public Optional<InetSocketAddress> nextCandidate(Set<InetSocketAddress> excluded) {
        List<InetAddress> addresses = resolveHosts();
        if (addresses.isEmpty()) {
            return Optional.empty();
        }
        int ports = toPort - fromPort + 1;
        long size = (long) addresses.size() * ports;
        if (size <= ENUMERATION_LIMIT) {
            List<InetSocketAddress> free = new ArrayList<>();
            for (InetAddress address : addresses) {
                for (int port = fromPort; port <= toPort; port++) {
                    InetSocketAddress candidate = new InetSocketAddress(address, port);
                    if (!excluded.contains(candidate)) {
                        free.add(candidate);
                    }
                }
            }
            return free.isEmpty() ? Optional.empty() : Optional.of(free.get(random.nextInt(free.size())));
        }
        for (int attempt = 0; attempt < RANDOM_ATTEMPTS; attempt++) {
            InetSocketAddress candidate = new InetSocketAddress(
                    addresses.get(random.nextInt(addresses.size())), fromPort + random.nextInt(ports));
            if (!excluded.contains(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private List<InetAddress> resolveHosts() {
        List<InetAddress> resolved = new ArrayList<>(hosts.size());
        for (String host : hosts) {
            try {
                resolved.add(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                // Skipped this round; it may resolve later.
            }
        }
        return resolved;
    }
}
