package at.ac.univie.dse.iot.msfog.aggregation;

import at.ac.univie.dse.iot.common.model.AggregationMode;
import java.util.List;
import java.util.OptionalDouble;

/** The aggregation strategies and the factory that picks one by {@link AggregationMode}. */
public final class AggregationStrategies {

    private AggregationStrategies() {
    }

    public static AggregationStrategy forMode(AggregationMode mode) {
        return switch (mode) {
            case AVG -> new Avg();
            case MAX -> new Max();
            case MIN -> new Min();
        };
    }

    /** Arithmetic mean. */
    public static final class Avg implements AggregationStrategy {
        @Override
        public OptionalDouble aggregate(List<Double> values) {
            return values.stream().mapToDouble(Double::doubleValue).average();
        }

        @Override
        public AggregationMode mode() {
            return AggregationMode.AVG;
        }
    }

    /** Largest value. */
    public static final class Max implements AggregationStrategy {
        @Override
        public OptionalDouble aggregate(List<Double> values) {
            return values.stream().mapToDouble(Double::doubleValue).max();
        }

        @Override
        public AggregationMode mode() {
            return AggregationMode.MAX;
        }
    }

    /** Smallest value. */
    public static final class Min implements AggregationStrategy {
        @Override
        public OptionalDouble aggregate(List<Double> values) {
            return values.stream().mapToDouble(Double::doubleValue).min();
        }

        @Override
        public AggregationMode mode() {
            return AggregationMode.MIN;
        }
    }
}
