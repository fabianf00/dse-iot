package at.ac.univie.dse.iot.msml.anomaly;

import java.time.Instant;

/** A phase that held: all {@code samples} values between {@code from} and {@code to} satisfied {@code condition}. */
public record PhaseObservation(String condition, Instant from, Instant to, int samples) {
}
