package at.ac.univie.dse.iot.msiot.sensor;

import at.ac.univie.dse.iot.common.model.SensorReading;
import java.util.Optional;

/** A sensor of an IoT device. Each device has exactly one. */
public interface Sensor {

    /** Takes one measurement; empty if the sensor produced no valid value this time. */
    Optional<SensorReading> read();
}
