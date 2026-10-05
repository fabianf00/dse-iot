package at.ac.univie.dse.iot.common.config;

import at.ac.univie.dse.iot.common.json.JsonCodec;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads JSON config files into typed config records and validates them.
 *
 * <p>Unlike message parsing, config parsing is strict: unknown properties are errors, so typos
 * in a config file are reported instead of silently ignored. Missing numeric fields default to
 * 0, so configs must validate them (e.g. {@link ConfigValidator#positive(String, long)}). Every
 * failure becomes a
 * {@link ConfigException} with a message that names the file, the field and the problem.
 */
public final class ConfigLoader {

    private final ObjectMapper mapper;

    public ConfigLoader() {
        this(JsonCodec.shared());
    }

    public ConfigLoader(JsonCodec codec) {
        // Missing numbers become 0 and are reported by validation (e.g. "must be > 0"). Failing
        // on them during parsing would hide typos: Jackson builds a record before it reports
        // unknown properties, so "publishIntervallMs" would surface as a missing
        // "publishIntervalMs" instead of as the typo.
        this.mapper = codec.copyMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public <C extends ValidatableConfig> C load(Path file, Class<C> type) {
        Objects.requireNonNull(file, "file");
        String json;
        try {
            json = Files.readString(file);
        } catch (NoSuchFileException e) {
            throw new ConfigException("Config file not found: " + file.toAbsolutePath());
        } catch (IOException e) {
            throw new ConfigException("Cannot read config file " + file.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        return parse(json, type, file.toString());
    }

    /**
     * Parses and validates a config.
     *
     * @param sourceName shown in error messages, e.g. the file name
     */
    public <C extends ValidatableConfig> C parse(String json, Class<C> type, String sourceName) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(type, "type");
        C config;
        try {
            config = mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new ConfigException("Invalid configuration in " + sourceName + ": " + describe(e), e);
        }
        if (config == null) {
            throw new ConfigException("Invalid configuration in " + sourceName + ": no content");
        }
        ConfigValidator validator = new ConfigValidator();
        config.validate(validator);
        validator.throwIfInvalid(sourceName);
        return config;
    }

    private static String describe(JsonProcessingException e) {
        StringBuilder description = new StringBuilder();
        if (e instanceof JsonMappingException mappingException) {
            String path = pathOf(mappingException);
            if (!path.isEmpty()) {
                description.append(path).append(": ");
            }
        }
        description.append(rootMessage(e));
        JsonLocation location = e.getLocation();
        if (location != null && location.getLineNr() > 0) {
            description.append(" (line ").append(location.getLineNr())
                    .append(", column ").append(location.getColumnNr()).append(')');
        }
        return description.toString();
    }

    /** Builds a dotted path like {@code sensor.generator.kind} or {@code fogNodes[1].port}. */
    private static String pathOf(JsonMappingException e) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : e.getPath()) {
            if (reference.getFieldName() != null) {
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    /**
     * Prefers the message of a validation exception thrown by a record constructor over
     * Jackson's generic wrapper text.
     */
    private static String rootMessage(JsonProcessingException e) {
        Throwable cause = e.getCause();
        if (cause instanceof IllegalArgumentException || cause instanceof NullPointerException) {
            return cause.getMessage();
        }
        return e.getOriginalMessage();
    }
}
