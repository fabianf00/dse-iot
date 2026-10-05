package at.ac.univie.dse.iot.msml.anomaly;

import java.time.Instant;

/**
 * Past and current phase of {@code chain} held at {@code createdAt}, so the future phase is
 * expected until {@code deadline}.
 */
public record Expectation(BehaviourChain chain, Instant createdAt, Instant deadline, PhaseObservation past,
        PhaseObservation current) {
}
