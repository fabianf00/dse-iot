package at.ac.univie.dse.iot.msfog.aggregation;

import at.ac.univie.dse.iot.common.model.AggregationMode;
import java.util.List;
import java.util.OptionalDouble;

/** Combines the readings of one window into one value (Strategy). */
public interface AggregationStrategy {

    /** The aggregate of {@code values}; empty if there are none. */
    OptionalDouble aggregate(List<Double> values);

    AggregationMode mode();
}
