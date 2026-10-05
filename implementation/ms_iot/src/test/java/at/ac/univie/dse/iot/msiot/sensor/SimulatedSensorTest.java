package at.ac.univie.dse.iot.msiot.sensor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.SensorReading;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.msiot.generator.ValueRange;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulatedSensorTest {

    private static final Instant NOW = Instant.parse("2021-11-20T14:03:05Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final DataType CO2 = DataType.of("co2");

    private static SimulatedSensor sensor(ValueRange range, Double... values) {
        Deque<Double> queue = new ArrayDeque<>(List.of(values));
        return new SimulatedSensor("iot-co2-1", CO2, Unit.PPM, at -> queue.poll(), range, CLOCK);
    }

    @Test
    void readingsCarryDeviceDataAndIncreasingSequence() {
        SimulatedSensor sensor = sensor(ValueRange.UNBOUNDED, 500.0, 510.0);

        SensorReading first = sensor.read().orElseThrow();
        SensorReading second = sensor.read().orElseThrow();

        assertEquals("iot-co2-1", first.deviceId());
        assertEquals(CO2, first.dataType());
        assertEquals(Unit.PPM, first.unit());
        assertEquals(500.0, first.value());
        assertEquals(NOW.toEpochMilli(), first.measuredAt());
        assertEquals(0, first.sequence());
        assertEquals(1, second.sequence());
        assertEquals(sensor.bootId(), first.bootId());
        assertEquals(first.bootId(), second.bootId());
    }

    @Test
    void valuesAreClampedToThePlausibleRange() {
        SimulatedSensor sensor = sensor(new ValueRange(380.0, 5000.0), 100.0, 9000.0);

        assertEquals(380.0, sensor.read().orElseThrow().value());
        assertEquals(5000.0, sensor.read().orElseThrow().value());
    }

    @Test
    void nonFiniteValuesAreSkippedWithoutUsingASequenceNumber() {
        SimulatedSensor sensor = sensor(ValueRange.UNBOUNDED, Double.NaN, Double.POSITIVE_INFINITY, 450.0);

        assertTrue(sensor.read().isEmpty());
        assertTrue(sensor.read().isEmpty());
        assertEquals(0, sensor.read().orElseThrow().sequence());
    }

    @Test
    void everySensorInstanceHasItsOwnBootId() {
        assertTrue(!sensor(ValueRange.UNBOUNDED).bootId().equals(sensor(ValueRange.UNBOUNDED).bootId()));
    }
}
