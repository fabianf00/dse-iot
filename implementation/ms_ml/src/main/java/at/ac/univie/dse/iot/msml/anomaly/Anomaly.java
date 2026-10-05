package at.ac.univie.dse.iot.msml.anomaly;

import at.ac.univie.dse.iot.common.model.Unit;
import java.time.Instant;

/**
 * A value broke the expectation of a behaviour chain.
 *
 * @param expectation   the broken expectation, with the observed past and current phases
 * @param observedValue the value that broke it
 * @param observedAt    when it was received
 * @param unit          unit of the value, {@code null} if unknown
 */
public record Anomaly(Expectation expectation, double observedValue, Instant observedAt, Unit unit) {

    public BehaviourChain chain() {
        return expectation.chain();
    }

    /** The failed expectation, e.g. {@code temperature > 20.0}. */
    public String expected() {
        return chain().future().condition().describe(chain().future().dataType());
    }
}
