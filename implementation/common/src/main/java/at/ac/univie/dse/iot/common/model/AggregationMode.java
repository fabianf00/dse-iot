package at.ac.univie.dse.iot.common.model;

/**
 * How a fog node combines the readings of one window into one value.
 *
 * <p>Shared because both MS Fog (producer) and MS ML (consumer) need it.
 */
public enum AggregationMode {
    AVG,
    MAX,
    MIN
}
