package at.ac.univie.dse.iot.mlapi;

import java.time.Instant;

/**
 * The most likely value range of a data type changed.
 *
 * @param previous the prediction before, {@code null} for the first prediction of the data type
 * @param current  the new prediction
 */
public record PredictionChangeDto(long sequence, Instant occurredAt, String dataType, PredictionDto previous,
        PredictionDto current) implements MlEventDto {

    private static final long serialVersionUID = 1L;
}
