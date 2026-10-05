package at.ac.univie.dse.iot.msml.ingest;

import at.ac.univie.dse.iot.common.model.AggregatedValue;
import java.time.Instant;

/** Receives every new, in-order aggregate of every fog. Called on the UDP receiver thread; must not block. */
@FunctionalInterface
public interface AggregateListener {

    void onAggregate(String fogId, AggregatedValue aggregate, Instant receivedAt);
}
