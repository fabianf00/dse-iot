package at.ac.univie.dse.iot.cf;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;

/** Chooses the next address the scanner probes for a peer. */
@FunctionalInterface
interface ScanStrategy {

    /** Returns a candidate not in {@code excluded}, or empty if there is none. */
    Optional<InetSocketAddress> nextCandidate(Set<InetSocketAddress> excluded);
}
