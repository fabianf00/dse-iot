package at.ac.univie.dse.iot.msml.data;

import java.time.Instant;

/** One aggregated value, timestamped with the time MS ML received it. */
public record DataPoint(Instant at, double value) {
}
