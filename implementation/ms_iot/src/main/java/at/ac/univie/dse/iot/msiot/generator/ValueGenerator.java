package at.ac.univie.dse.iot.msiot.generator;

import java.time.Instant;

/**
 * Produces the raw values of a simulated sensor (Strategy). Implementations may keep state
 * between calls and are used by one thread at a time.
 */
@FunctionalInterface
public interface ValueGenerator {

    /** Value measured at {@code at}. May be non-finite if the generator is broken; callers check. */
    double next(Instant at);
}
