package at.ac.univie.dse.iot.msml.storage;

import jakarta.persistence.Embeddable;
import java.time.Instant;

/** A behaviour chain phase that held, stored inside its anomaly. */
@Embeddable
public class PhaseObservationEmbeddable {

    private String phaseCondition;
    private Instant periodStart;
    private Instant periodEnd;
    private Integer samples;

    /** For JPA. */
    protected PhaseObservationEmbeddable() {
    }

    public PhaseObservationEmbeddable(String condition, Instant periodStart, Instant periodEnd, int samples) {
        this.phaseCondition = condition;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.samples = samples;
    }

    public String getCondition() {
        return phaseCondition;
    }

    public Instant getPeriodStart() {
        return periodStart;
    }

    public Instant getPeriodEnd() {
        return periodEnd;
    }

    public Integer getSamples() {
        return samples;
    }
}
