package at.ac.univie.dse.iot.msml.anomaly;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import java.util.List;

/**
 * Anomaly detection settings.
 *
 * @param minSamplesPerPhase a phase only holds if it contains at least this many values,
 *                           default {@value #DEFAULT_MIN_SAMPLES_PER_PHASE}
 * @param chains             the behaviour chains, with unique ids
 */
public record AnomalyConfig(int minSamplesPerPhase, List<BehaviourChain> chains) implements ValidatableConfig {

    public static final int DEFAULT_MIN_SAMPLES_PER_PHASE = 2;

    public AnomalyConfig {
        minSamplesPerPhase = minSamplesPerPhase == 0 ? DEFAULT_MIN_SAMPLES_PER_PHASE : minSamplesPerPhase;
        chains = chains == null ? null : List.copyOf(chains);
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.positive("minSamplesPerPhase", minSamplesPerPhase)
                .notEmpty("chains", chains)
                .each("chains", chains);
        if (chains != null) {
            validator.check(chains.stream().map(BehaviourChain::id).distinct().count() == chains.size(),
                    "chains", "must have unique ids");
        }
    }

    /** Longest {@link BehaviourChain#spanSeconds()} of all chains. */
    public long maxSpanSeconds() {
        return chains.stream().mapToLong(BehaviourChain::spanSeconds).max().orElse(0);
    }
}
