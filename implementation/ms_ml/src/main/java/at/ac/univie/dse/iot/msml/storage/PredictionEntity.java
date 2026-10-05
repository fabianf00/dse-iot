package at.ac.univie.dse.iot.msml.storage;

import at.ac.univie.dse.iot.msml.events.MlEvent;
import at.ac.univie.dse.iot.msml.prediction.Prediction;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.time.Instant;

/** A stored prediction change. Columns of subclasses are nullable (single-table inheritance). */
@Entity
@DiscriminatorValue("PREDICTION")
public class PredictionEntity extends MlResultEntity {

    @Column(length = 16)
    private String unit;

    private Double binLower;
    private Double binUpper;
    private Double mostLikelyValue;
    private Double confidence;
    private Integer sampleCount;
    private Double previousBinLower;
    private Double previousBinUpper;

    /** For JPA. */
    protected PredictionEntity() {
    }

    /** The stored form of a prediction change event of the ML instance {@code mlInstance}. */
    public PredictionEntity(String mlInstance, long serverEpoch, MlEvent.PredictionChangedEvent event, Instant recordedAt) {
        super(mlInstance, serverEpoch, event.sequence(), event.dataType().value(), event.occurredAt(), recordedAt);
        Prediction current = event.change().current();
        Prediction previous = event.change().previous();
        this.unit = current.unit() == null ? null : current.unit().name();
        this.binLower = current.binLower();
        this.binUpper = current.binUpper();
        this.mostLikelyValue = current.mostLikelyValue();
        this.confidence = current.confidence();
        this.sampleCount = current.sampleCount();
        this.previousBinLower = previous == null ? null : previous.binLower();
        this.previousBinUpper = previous == null ? null : previous.binUpper();
    }

    @Override
    public ResultKind kind() {
        return ResultKind.PREDICTION;
    }

    public String getUnit() {
        return unit;
    }

    public Double getBinLower() {
        return binLower;
    }

    public Double getBinUpper() {
        return binUpper;
    }

    public Double getMostLikelyValue() {
        return mostLikelyValue;
    }

    public Double getConfidence() {
        return confidence;
    }

    public Integer getSampleCount() {
        return sampleCount;
    }

    public Double getPreviousBinLower() {
        return previousBinLower;
    }

    public Double getPreviousBinUpper() {
        return previousBinUpper;
    }
}
