package at.ac.univie.dse.iot.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Result of aggregating the readings of one data type over one window. Produced by MS Fog and
 * consumed by MS ML.
 *
 * <p>An empty window ({@code sampleCount == 0}) has no value. Fog nodes still publish it, so
 * consumers can tell "fog alive but no sensor data" from "fog unreachable".
 *
 * @param dataType        aggregated data type
 * @param unit            unit of {@code value}
 * @param aggregationMode how the readings were combined
 * @param windowStart     window start, epoch milliseconds (fog clock, receive time)
 * @param windowEnd       window end, epoch milliseconds, {@code >= windowStart}
 * @param sampleCount     number of readings in the window
 * @param deviceCount     number of distinct devices that contributed, {@code <= sampleCount}
 * @param value           aggregated value, {@code null} exactly when the window is empty
 */
public record AggregatedValue(
        DataType dataType,
        Unit unit,
        AggregationMode aggregationMode,
        long windowStart,
        long windowEnd,
        int sampleCount,
        int deviceCount,
        Double value) {

    public AggregatedValue {
        Objects.requireNonNull(dataType, "dataType");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(aggregationMode, "aggregationMode");
        if (windowEnd < windowStart) {
            throw new IllegalArgumentException("windowEnd must not be before windowStart");
        }
        if (sampleCount < 0) {
            throw new IllegalArgumentException("sampleCount must be >= 0 but was " + sampleCount);
        }
        if (deviceCount < 0 || deviceCount > sampleCount) {
            throw new IllegalArgumentException(
                    "deviceCount must be between 0 and sampleCount (" + sampleCount + ") but was " + deviceCount);
        }
        if (sampleCount == 0 && value != null) {
            throw new IllegalArgumentException("an empty window must not have a value");
        }
        if (sampleCount > 0 && value == null) {
            throw new IllegalArgumentException("a non-empty window must have a value");
        }
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite but was " + value);
        }
    }

    /** Creates the aggregate of a window that received no readings. */
    public static AggregatedValue empty(
            DataType dataType, Unit unit, AggregationMode aggregationMode, long windowStart, long windowEnd) {
        return new AggregatedValue(dataType, unit, aggregationMode, windowStart, windowEnd, 0, 0, null);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return sampleCount == 0;
    }

    /** The value as an optional, empty for an empty window. */
    @JsonIgnore
    public OptionalDouble optionalValue() {
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }
}
