package at.ac.univie.dse.iot.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Name of a sensor data type, e.g. {@code temperature} or {@code co2}.
 *
 * <p>Modelled as a value object instead of an enum, so a new sensor type only needs new config
 * files. Names are normalized to lower case and must match {@code [a-z][a-z0-9_-]*}. In JSON a
 * data type is a plain string.
 */
public record DataType(String value) implements Serializable {

    private static final Pattern VALID_NAME = Pattern.compile("[a-z][a-z0-9_-]*");

    public DataType {
        if (value == null) {
            throw new IllegalArgumentException("data type must not be null");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!VALID_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "invalid data type '" + value + "': expected a name matching " + VALID_NAME.pattern());
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DataType of(String value) {
        return new DataType(value);
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
