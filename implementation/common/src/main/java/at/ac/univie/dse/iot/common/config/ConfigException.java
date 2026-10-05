package at.ac.univie.dse.iot.common.config;

import java.util.List;

/**
 * Thrown when a configuration cannot be read or is invalid. The message is meant for the user
 * and names the source and every problem found.
 */
public class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> problems;

    public ConfigException(String message) {
        super(message);
        this.problems = List.of(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
        this.problems = List.of(message);
    }

    /** Creates an exception listing all validation problems of {@code source}. */
    public static ConfigException invalid(String source, List<String> problems) {
        StringBuilder message = new StringBuilder("Invalid configuration in ").append(source).append(':');
        problems.forEach(problem -> message.append(System.lineSeparator()).append("  - ").append(problem));
        return new ConfigException(message.toString(), problems);
    }

    private ConfigException(String message, List<String> problems) {
        super(message);
        this.problems = List.copyOf(problems);
    }

    /** The individual problems, e.g. {@code sensor.publishIntervalMs must be > 0}. */
    public List<String> problems() {
        return problems;
    }
}
