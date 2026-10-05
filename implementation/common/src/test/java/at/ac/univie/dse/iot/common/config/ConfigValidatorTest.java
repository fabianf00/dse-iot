package at.ac.univie.dse.iot.common.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {

    @Test
    void validValuesProduceNoProblems() {
        ConfigValidator validator = new ConfigValidator()
                .notNull("a", 1)
                .notBlank("b", "x")
                .notEmpty("c", List.of(1))
                .positive("d", 1L)
                .positive("e", 0.5)
                .nonNegative("f", 0)
                .inRange("g", 5, 1, 10)
                .port("h", 65535);

        assertFalse(validator.hasProblems());
        assertDoesNotThrow(() -> validator.throwIfInvalid("x"));
    }

    @Test
    void eachCheckReportsItsProblem() {
        ConfigValidator validator = new ConfigValidator()
                .notNull("a", null)
                .notBlank("b", " ")
                .notEmpty("c", List.of())
                .positive("d", 0L)
                .positive("e", -0.5)
                .nonNegative("f", -1)
                .inRange("g", 11, 1, 10)
                .port("h", 0);

        assertEquals(List.of(
                "a must be set",
                "b must not be blank",
                "c must not be empty",
                "d must be > 0",
                "e must be > 0",
                "f must be >= 0",
                "g must be between 1 and 10",
                "h must be between 1 and 65535"), validator.problems());
    }

    @Test
    void nestedPathsAreJoinedWithDots() {
        ValidatableConfig inner = v -> v.positive("windowMs", 0);
        ValidatableConfig middle = v -> v.nested("inner", inner);

        ConfigValidator validator = new ConfigValidator().nested("outer", middle);

        assertEquals(List.of("outer.inner.windowMs must be > 0"), validator.problems());
    }

    @Test
    void throwIfInvalidListsAllProblems() {
        ConfigValidator validator = new ConfigValidator().positive("x", 0).notBlank("y", null);

        ConfigException e = assertThrows(ConfigException.class, () -> validator.throwIfInvalid("cfg.json"));

        assertEquals(List.of("x must be > 0", "y must not be blank"), e.problems());
    }
}
