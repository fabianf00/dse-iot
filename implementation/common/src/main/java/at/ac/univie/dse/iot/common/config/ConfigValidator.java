package at.ac.univie.dse.iot.common.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Collects validation problems of a configuration, each prefixed with the dotted path of the
 * field, e.g. {@code sensor.publishIntervalMs must be > 0}.
 *
 * <p>All check methods return {@code this} so checks can be chained.
 */
public final class ConfigValidator {

    private final String path;
    private final List<String> problems;

    public ConfigValidator() {
        this("", new ArrayList<>());
    }

    private ConfigValidator(String path, List<String> problems) {
        this.path = path;
        this.problems = problems;
    }

    /** Records {@code problem} for {@code field} unless {@code condition} holds. */
    public ConfigValidator check(boolean condition, String field, String problem) {
        if (!condition) {
            problems.add(pathOf(field) + " " + problem);
        }
        return this;
    }

    public ConfigValidator notNull(String field, Object value) {
        return check(value != null, field, "must be set");
    }

    public ConfigValidator notBlank(String field, String value) {
        return check(value != null && !value.isBlank(), field, "must not be blank");
    }

    public ConfigValidator notEmpty(String field, Collection<?> values) {
        return check(values != null && !values.isEmpty(), field, "must not be empty");
    }

    public ConfigValidator positive(String field, long value) {
        return check(value > 0, field, "must be > 0");
    }

    public ConfigValidator positive(String field, double value) {
        return check(value > 0, field, "must be > 0");
    }

    public ConfigValidator nonNegative(String field, long value) {
        return check(value >= 0, field, "must be >= 0");
    }

    public ConfigValidator inRange(String field, long value, long min, long max) {
        return check(value >= min && value <= max, field, "must be between " + min + " and " + max);
    }

    public ConfigValidator port(String field, int port) {
        return inRange(field, port, 1, 65535);
    }

    /** Validates a nested config under {@code field}, or records that it is missing. */
    public ConfigValidator nested(String field, ValidatableConfig child) {
        if (child == null) {
            return notNull(field, null);
        }
        child.validate(new ConfigValidator(pathOf(field), problems));
        return this;
    }

    /** Validates every element of a list of nested configs as {@code field[i]}. */
    public ConfigValidator each(String field, List<? extends ValidatableConfig> children) {
        if (children == null) {
            return notNull(field, null);
        }
        for (int i = 0; i < children.size(); i++) {
            nested(field + "[" + i + "]", children.get(i));
        }
        return this;
    }

    public boolean hasProblems() {
        return !problems.isEmpty();
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Throws a {@link ConfigException} naming {@code source} if any problem was recorded. */
    public void throwIfInvalid(String source) {
        if (hasProblems()) {
            throw ConfigException.invalid(source, problems);
        }
    }

    private String pathOf(String field) {
        return path.isEmpty() ? field : path + "." + field;
    }
}
