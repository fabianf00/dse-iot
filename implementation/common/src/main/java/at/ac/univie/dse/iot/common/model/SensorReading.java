package at.ac.univie.dse.iot.common.model;

import java.util.Objects;
import java.util.UUID;

/**
 * One value measured by a simulated IoT device. Published by MS IoT on topic
 * {@code sensor.<dataType>} (see {@link Topics#sensor(DataType)}).
 *
 * @param deviceId   id of the publishing device, equals its {@code serviceId}
 * @param dataType   data type of the device's sensor
 * @param unit       unit of {@code value}
 * @param value      measured value, always finite
 * @param sequence   per-device counter, strictly increasing, starts at 0 on every device start
 * @param bootId     random id chosen on every device start, so consumers can tell a restart
 *                   (sequence back to 0) from reordering
 * @param measuredAt device clock in epoch milliseconds, for information only
 */
public record SensorReading(
        String deviceId,
        DataType dataType,
        Unit unit,
        double value,
        long sequence,
        UUID bootId,
        long measuredAt) {

    public SensorReading {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        Objects.requireNonNull(dataType, "dataType");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(bootId, "bootId");
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite but was " + value);
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0 but was " + sequence);
        }
    }
}
