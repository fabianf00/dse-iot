package at.ac.univie.dse.iot.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelTest {

    private static final DataType TEMPERATURE = DataType.of("temperature");

    @Test
    void dataTypeIsNormalizedToLowerCase() {
        assertEquals(DataType.of("co2"), DataType.of(" CO2 "));
        assertEquals("co2", DataType.of("Co2").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "2fast", "air pressure", "temp.inside", "sensor*"})
    void dataTypeRejectsInvalidNames(String name) {
        assertThrows(IllegalArgumentException.class, () -> DataType.of(name));
    }

    @Test
    void dataTypeRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> DataType.of(null));
    }

    @Test
    void sensorTopicIsDerivedFromDataType() {
        assertEquals("sensor.temperature", Topics.sensor(TEMPERATURE));
        assertEquals("sensor.*", Topics.ALL_SENSORS);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void sensorReadingRejectsNonFiniteValues(double value) {
        assertThrows(IllegalArgumentException.class, () -> new SensorReading(
                "iot-temp-1", TEMPERATURE, Unit.CELSIUS, value, 0, UUID.randomUUID(), 0));
    }

    @Test
    void sensorReadingRejectsBlankDeviceIdAndNegativeSequence() {
        UUID bootId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new SensorReading(" ", TEMPERATURE, Unit.CELSIUS, 1, 0, bootId, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SensorReading("d", TEMPERATURE, Unit.CELSIUS, 1, -1, bootId, 0));
        assertThrows(NullPointerException.class,
                () -> new SensorReading("d", TEMPERATURE, Unit.CELSIUS, 1, 0, null, 0));
    }

    @Test
    void emptyAggregateHasNoValue() {
        AggregatedValue empty = AggregatedValue.empty(TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 0, 10);

        assertTrue(empty.isEmpty());
        assertEquals(OptionalDouble.empty(), empty.optionalValue());
    }

    @Test
    void nonEmptyAggregateExposesValue() {
        AggregatedValue value = new AggregatedValue(TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, 0, 10, 3, 2, 20.0);

        assertFalse(value.isEmpty());
        assertEquals(OptionalDouble.of(20.0), value.optionalValue());
    }

    @Test
    void aggregateRejectsInconsistentFields() {
        assertThrows(IllegalArgumentException.class, () -> aggregate(10, 0, 0, 0, null),
                "window end before start");
        assertThrows(IllegalArgumentException.class, () -> aggregate(0, 10, 0, 0, 1.0),
                "empty window with value");
        assertThrows(IllegalArgumentException.class, () -> aggregate(0, 10, 2, 1, null),
                "non-empty window without value");
        assertThrows(IllegalArgumentException.class, () -> aggregate(0, 10, 2, 3, 1.0),
                "more devices than samples");
        assertThrows(IllegalArgumentException.class, () -> aggregate(0, 10, -1, 0, null),
                "negative sample count");
        assertThrows(IllegalArgumentException.class, () -> aggregate(0, 10, 1, 1, Double.NaN),
                "non-finite value");
    }

    private static AggregatedValue aggregate(long start, long end, int samples, int devices, Double value) {
        return new AggregatedValue(TEMPERATURE, Unit.CELSIUS, AggregationMode.AVG, start, end, samples, devices, value);
    }
}
