package at.ac.univie.dse.iot.msiot.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GeneratorsTest {

    private static final double EPSILON = 1e-6;
    private static final Instant T0 = Instant.ofEpochSecond(1_000_000_100L);

    /** Random whose nextDouble() returns scripted values. */
    private static final class ScriptedRandom extends Random {
        private final Deque<Double> doubles;

        ScriptedRandom(Double... values) {
            this.doubles = new ArrayDeque<>(List.of(values));
        }

        @Override
        public double nextDouble() {
            return doubles.isEmpty() ? 1.0 : doubles.poll();
        }
    }

    @Test
    void constantAlwaysReturnsItsValue() {
        ConstantGenerator generator = new ConstantGenerator(21.5);

        assertEquals(21.5, generator.next(T0));
        assertEquals(21.5, generator.next(T0.plusSeconds(3600)));
    }

    @Test
    void sineWithoutNoiseFollowsTheWave() {
        // Period 400 s; epoch second 1_000_000_100 is 100 s (a quarter period) into a cycle.
        SineWaveGenerator generator = new SineWaveGenerator(20, 3, 400, 0, 0, new Random(1));

        assertEquals(23, generator.next(T0), EPSILON, "peak at a quarter period");
        assertEquals(20, generator.next(T0.plusSeconds(100)), EPSILON, "base at half period");
        assertEquals(17, generator.next(T0.plusSeconds(200)), EPSILON, "trough at three quarters");
        assertEquals(23, generator.next(T0.plusSeconds(400)), EPSILON, "repeats after one period");
    }

    @Test
    void sinePhaseShiftsTheWave() {
        SineWaveGenerator shifted = new SineWaveGenerator(20, 3, 400, 100, 0, new Random(1));

        assertEquals(20, shifted.next(T0), EPSILON);
    }

    @Test
    void sineNoiseIsReproducibleWithASeed() {
        SineWaveGenerator first = new SineWaveGenerator(20, 3, 400, 0, 0.5, new Random(42));
        SineWaveGenerator second = new SineWaveGenerator(20, 3, 400, 0, 0.5, new Random(42));

        for (int i = 0; i < 10; i++) {
            Instant at = T0.plusSeconds(i);
            assertEquals(first.next(at), second.next(at));
        }
    }

    @Test
    void sineRejectsNonPositivePeriod() {
        assertThrows(IllegalArgumentException.class, () -> new SineWaveGenerator(0, 1, 0, 0, 0, new Random()));
    }

    @Test
    void randomWalkStaysInsideItsRange() {
        RandomWalkGenerator walk = new RandomWalkGenerator(470, 50, 0, new ValueRange(400.0, 500.0), new Random(3));

        for (int i = 0; i < 10_000; i++) {
            double value = walk.next(T0);
            assertTrue(value >= 400 && value <= 500, "value " + value + " outside range");
        }
    }

    @Test
    void randomWalkRevertsToItsMean() {
        RandomWalkGenerator walk = new RandomWalkGenerator(23, 0.1, 0.2, ValueRange.UNBOUNDED, new Random(5));

        double sum = 0;
        int samples = 5_000;
        for (int i = 0; i < samples; i++) {
            sum += walk.next(T0);
        }
        assertEquals(23, sum / samples, 0.1);
    }

    @Test
    void randomWalkRejectsInvalidMeanReversion() {
        assertThrows(IllegalArgumentException.class,
                () -> new RandomWalkGenerator(0, 1, 1.5, ValueRange.UNBOUNDED, new Random()));
    }

    @Test
    void anomalyShiftsValuesForItsDurationAndThenEnds() {
        // First draw starts an anomaly; later draws (0.9) never start a new one.
        AnomalyInjectingGenerator generator = new AnomalyInjectingGenerator(
                new ConstantGenerator(20), 0.5, -8, Duration.ofSeconds(60), new ScriptedRandom(0.1, 0.9, 0.9));

        assertEquals(12, generator.next(T0));
        assertTrue(generator.isAnomalyActive());
        assertEquals(12, generator.next(T0.plusSeconds(59)));
        assertEquals(20, generator.next(T0.plusSeconds(60)));
        assertFalse(generator.isAnomalyActive());
        assertEquals(20, generator.next(T0.plusSeconds(61)));
    }

    @Test
    void anomalyNeverStartsWhenDrawsAreAboveTheProbability() {
        AnomalyInjectingGenerator generator = new AnomalyInjectingGenerator(
                new ConstantGenerator(20), 0.01, 100, Duration.ofSeconds(60), new Random(9));

        int shifted = 0;
        for (int i = 0; i < 10_000; i++) {
            if (generator.next(T0.plusSeconds(i * 60L)) != 20) {
                shifted++;
            }
        }
        assertTrue(shifted > 50 && shifted < 200, "about 1 % of readings start an anomaly, got " + shifted);
    }

    @Test
    void rangeClampsToItsBounds() {
        ValueRange range = new ValueRange(0.0, 10.0);

        assertEquals(0, range.clamp(-5));
        assertEquals(10, range.clamp(15));
        assertEquals(7, range.clamp(7));
        assertEquals(-1e9, ValueRange.UNBOUNDED.clamp(-1e9));
        assertEquals(10, new ValueRange(null, 10.0).clamp(11));
        assertThrows(IllegalArgumentException.class, () -> new ValueRange(5.0, 1.0));
    }
}
