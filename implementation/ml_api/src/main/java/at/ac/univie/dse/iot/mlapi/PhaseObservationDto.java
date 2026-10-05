package at.ac.univie.dse.iot.mlapi;

import java.io.Serializable;
import java.time.Instant;

/**
 * A phase of a behaviour chain that held: every value of {@code dataType} between {@code from}
 * and {@code to} satisfied {@code condition}.
 *
 * @param condition e.g. {@code temperature > 20.0}
 * @param samples   number of values in the period
 */
public record PhaseObservationDto(String condition, Instant from, Instant to, int samples) implements Serializable {

    private static final long serialVersionUID = 1L;
}
