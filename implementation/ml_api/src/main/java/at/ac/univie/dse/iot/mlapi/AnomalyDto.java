package at.ac.univie.dse.iot.mlapi;

import java.time.Instant;

/**
 * A behaviour chain was broken: past and current behaviour were observed, but the expected
 * future behaviour was not.
 *
 * @param dataType         data type of the value that broke the expectation
 * @param chainId          id of the behaviour chain, e.g. {@code temp-sudden-drop}
 * @param chainDescription what the chain means
 * @param past             what was observed in the past phase
 * @param current          what was observed in the current phase
 * @param expected         the expectation that failed, e.g. {@code temperature > 20.0}
 * @param observedValue    the value that broke it
 * @param unit             unit of {@code observedValue}, e.g. {@code CELSIUS}
 * @param expectedUntil    end of the period the expectation covered
 */
public record AnomalyDto(
        long sequence,
        Instant occurredAt,
        String dataType,
        String chainId,
        String chainDescription,
        PhaseObservationDto past,
        PhaseObservationDto current,
        String expected,
        double observedValue,
        String unit,
        Instant expectedUntil) implements MlEventDto {

    private static final long serialVersionUID = 1L;
}
