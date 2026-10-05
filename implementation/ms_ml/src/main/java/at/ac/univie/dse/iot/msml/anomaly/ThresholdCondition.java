package at.ac.univie.dse.iot.msml.anomaly;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import at.ac.univie.dse.iot.common.model.DataType;

/**
 * Compares a value with a threshold. In JSON: {@code { "op": "GT", "value": 20.0 }}.
 */
public record ThresholdCondition(Operator op, double value) implements Condition, ValidatableConfig {

    /** Comparison operators. */
    public enum Operator {
        GT(">"),
        GE(">="),
        LT("<"),
        LE("<=");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        boolean test(double actual, double threshold) {
            return switch (this) {
                case GT -> actual > threshold;
                case GE -> actual >= threshold;
                case LT -> actual < threshold;
                case LE -> actual <= threshold;
            };
        }

        public String symbol() {
            return symbol;
        }
    }

    @Override
    public boolean test(double actual) {
        return op.test(actual, value);
    }

    @Override
    public String describe(DataType dataType) {
        return dataType + " " + op.symbol() + " " + value;
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notNull("op", op);
    }
}
