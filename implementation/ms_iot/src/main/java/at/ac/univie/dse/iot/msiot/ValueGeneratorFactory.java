package at.ac.univie.dse.iot.msiot;

import at.ac.univie.dse.iot.msiot.generator.AnomalyInjectingGenerator;
import at.ac.univie.dse.iot.msiot.generator.ConstantGenerator;
import at.ac.univie.dse.iot.msiot.generator.RandomWalkGenerator;
import at.ac.univie.dse.iot.msiot.generator.SineWaveGenerator;
import at.ac.univie.dse.iot.msiot.generator.ValueGenerator;
import at.ac.univie.dse.iot.msiot.generator.ValueRange;
import java.time.Duration;
import java.util.Random;

/** Builds the generator chain of a sensor from its config. */
public final class ValueGeneratorFactory {

    /** Mixed into the seed of the anomaly injection, so it is independent of the value noise. */
    private static final long ANOMALY_SEED_SALT = 0x5DEECE66DL;

    private ValueGeneratorFactory() {
    }

    public static ValueGenerator create(SensorConfig sensor) {
        GeneratorConfig config = sensor.generator();
        ValueGenerator generator = switch (config.kind()) {
            case CONSTANT -> new ConstantGenerator(config.base());
            case SINE -> new SineWaveGenerator(config.base(), config.amplitude(), config.periodSeconds(),
                    config.phaseSeconds(), config.noiseStdDev(), random(config.seed(), 0));
            case RANDOM_WALK -> new RandomWalkGenerator(config.base(), config.stepStdDev(), config.meanReversion(),
                    rangeOf(config), random(config.seed(), 0));
        };
        AnomalyInjectionConfig anomalies = sensor.anomalyInjection();
        if (anomalies != null && anomalies.enabled()) {
            generator = new AnomalyInjectingGenerator(generator, anomalies.probabilityPerReading(), anomalies.offset(),
                    Duration.ofSeconds(anomalies.durationSeconds()), random(config.seed(), ANOMALY_SEED_SALT));
        }
        return generator;
    }

    public static ValueRange rangeOf(GeneratorConfig config) {
        return new ValueRange(config.min(), config.max());
    }

    private static Random random(Long seed, long salt) {
        return seed == null ? new Random() : new Random(seed ^ salt);
    }
}
