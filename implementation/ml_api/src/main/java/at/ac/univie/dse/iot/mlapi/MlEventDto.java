package at.ac.univie.dse.iot.mlapi;

import java.io.Serializable;
import java.time.Instant;

/** An event of MS ML: an {@link AnomalyDto} or a {@link PredictionChangeDto}. */
public sealed interface MlEventDto extends Serializable permits AnomalyDto, PredictionChangeDto {

    /** Position in the service's event log, strictly increasing within one server epoch. */
    long sequence();

    Instant occurredAt();

    /** Data type the event is about, e.g. {@code temperature}. */
    String dataType();
}
