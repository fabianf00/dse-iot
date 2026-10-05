package at.ac.univie.dse.iot.msml.anomaly;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import at.ac.univie.dse.iot.common.model.DataType;

/**
 * Behaviour chain Past ↦ Current ↦ Future: if the past and current phases were observed, the
 * future phase is expected. A future value that breaks the expectation is an anomaly. The phases
 * may concern different data types.
 */
public record BehaviourChain(String id, String description, Phase past, Phase current, FuturePhase future)
        implements ValidatableConfig {

    /** Whether a new value of {@code dataType} can change the state of this chain. */
    public boolean involves(DataType dataType) {
        return past.dataType().equals(dataType) || current.dataType().equals(dataType)
                || future.dataType().equals(dataType);
    }

    /** How far back the chain looks plus how far it looks ahead, in seconds. */
    public long spanSeconds() {
        return Math.max(past.fromSec(), current.fromSec()) + future.withinSec();
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notBlank("id", id)
                .nested("past", past)
                .nested("current", current)
                .nested("future", future);
    }
}
