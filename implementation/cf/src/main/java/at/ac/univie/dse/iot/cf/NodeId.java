package at.ac.univie.dse.iot.cf;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.regex.Pattern;

/**
 * Unique name of a CF peer, e.g. {@code iot-temp-1}. In JSON a node id is a plain string.
 *
 * <p>Allowed characters are letters, digits, {@code .}, {@code _} and {@code -}, at most 64.
 */
public record NodeId(String value) implements Comparable<NodeId> {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public NodeId {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "invalid node id '" + value + "': expected 1-64 characters of [A-Za-z0-9._-]");
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static NodeId of(String value) {
        return new NodeId(value);
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }

    @Override
    public int compareTo(NodeId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
