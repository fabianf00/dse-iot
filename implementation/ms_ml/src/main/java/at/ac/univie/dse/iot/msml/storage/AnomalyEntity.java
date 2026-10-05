package at.ac.univie.dse.iot.msml.storage;

import at.ac.univie.dse.iot.msml.anomaly.Anomaly;
import at.ac.univie.dse.iot.msml.anomaly.PhaseObservation;
import at.ac.univie.dse.iot.msml.events.MlEvent;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import java.time.Instant;

/** A stored anomaly. Columns of subclasses are nullable (single-table inheritance). */
@Entity
@DiscriminatorValue("ANOMALY")
public class AnomalyEntity extends MlResultEntity {

    @Column(length = 64)
    private String chainId;

    @Column(length = 500)
    private String chainDescription;

    @Column(length = 200)
    private String expected;

    private Double observedValue;

    @Column(length = 16)
    private String unit;

    private Instant observedAt;

    private Instant expectedUntil;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "phaseCondition", column = @Column(name = "past_condition", length = 200)),
        @AttributeOverride(name = "periodStart", column = @Column(name = "past_start")),
        @AttributeOverride(name = "periodEnd", column = @Column(name = "past_end")),
        @AttributeOverride(name = "samples", column = @Column(name = "past_samples"))
    })
    private PhaseObservationEmbeddable past;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "phaseCondition", column = @Column(name = "current_condition", length = 200)),
        @AttributeOverride(name = "periodStart", column = @Column(name = "current_start")),
        @AttributeOverride(name = "periodEnd", column = @Column(name = "current_end")),
        @AttributeOverride(name = "samples", column = @Column(name = "current_samples"))
    })
    private PhaseObservationEmbeddable current;

    /** For JPA. */
    protected AnomalyEntity() {
    }

    /** The stored form of an anomaly event of the ML instance {@code mlInstance}. */
    public AnomalyEntity(String mlInstance, long serverEpoch, MlEvent.AnomalyEvent event, Instant recordedAt) {
        super(mlInstance, serverEpoch, event.sequence(), event.dataType().value(), event.occurredAt(), recordedAt);
        Anomaly anomaly = event.anomaly();
        this.chainId = anomaly.chain().id();
        this.chainDescription = anomaly.chain().description();
        this.expected = anomaly.expected();
        this.observedValue = anomaly.observedValue();
        this.unit = anomaly.unit() == null ? null : anomaly.unit().name();
        this.observedAt = anomaly.observedAt();
        this.expectedUntil = anomaly.expectation().deadline();
        this.past = embed(anomaly.expectation().past());
        this.current = embed(anomaly.expectation().current());
    }

    private static PhaseObservationEmbeddable embed(PhaseObservation observation) {
        return new PhaseObservationEmbeddable(observation.condition(), observation.from(), observation.to(),
                observation.samples());
    }

    @Override
    public ResultKind kind() {
        return ResultKind.ANOMALY;
    }

    public String getChainId() {
        return chainId;
    }

    public String getChainDescription() {
        return chainDescription;
    }

    public String getExpected() {
        return expected;
    }

    public Double getObservedValue() {
        return observedValue;
    }

    public String getUnit() {
        return unit;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public Instant getExpectedUntil() {
        return expectedUntil;
    }

    public PhaseObservationEmbeddable getPast() {
        return past;
    }

    public PhaseObservationEmbeddable getCurrent() {
        return current;
    }
}
