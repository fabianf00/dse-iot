package at.ac.univie.dse.iot.common.model;

/** CF topic names shared by publishers and subscribers. */
public final class Topics {

    /** Prefix of all sensor reading topics. */
    public static final String SENSOR_PREFIX = "sensor.";

    /** Pattern matching the readings of every data type. */
    public static final String ALL_SENSORS = SENSOR_PREFIX + "*";

    private Topics() {
    }

    /** Topic on which readings of {@code dataType} are published, e.g. {@code sensor.temperature}. */
    public static String sensor(DataType dataType) {
        return SENSOR_PREFIX + dataType.value();
    }
}
