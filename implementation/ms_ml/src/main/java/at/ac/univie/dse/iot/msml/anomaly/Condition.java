package at.ac.univie.dse.iot.msml.anomaly;

import at.ac.univie.dse.iot.common.model.DataType;

/** A condition on single values, e.g. {@code > 20.0}. */
public interface Condition {

    boolean test(double value);

    /** Human-readable form for {@code dataType}, e.g. {@code temperature > 20.0}. */
    String describe(DataType dataType);
}
