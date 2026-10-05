package at.ac.univie.dse.iot.msml.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PredictionTest {

    private static final DataType CO2 = DataType.of("co2");
    private static final DataType TEMPERATURE = DataType.of("temperature");

    @Test
    void freedmanDiaconisUsesTheInterquartileRange() {
        // Quartiles of 1..8 with linear interpolation: 2.75 and 6.25, IQR 3.5; cbrt(8) = 2.
        assertEquals(3.5, FreedmanDiaconis.binWidth(new double[] {8, 1, 7, 2, 6, 3, 5, 4}), 1e-12);
    }

    @Test
    void quantilesInterpolateBetweenRanks() {
        double[] sorted = {10, 20, 30, 40, 50};

        assertEquals(10, FreedmanDiaconis.quantile(sorted, 0));
        assertEquals(30, FreedmanDiaconis.quantile(sorted, 0.5));
        assertEquals(25, FreedmanDiaconis.quantile(sorted, 0.375));
        assertEquals(50, FreedmanDiaconis.quantile(sorted, 1));
    }

    @Test
    void freedmanDiaconisRejectsDegenerateSamples() {
        assertThrows(IllegalArgumentException.class, () -> FreedmanDiaconis.binWidth(new double[] {1}));
        assertThrows(IllegalArgumentException.class, () -> FreedmanDiaconis.binWidth(new double[] {5, 5, 5, 5}));
    }

    @Test
    void histogramModeIsTheFullestBin() {
        Histogram histogram = new Histogram(1.0, 0.0, 100);
        for (double value : new double[] {9.5, 10.1, 10.9, 10.5, 11.2}) {
            histogram.add(value);
        }

        Histogram.Bin mode = histogram.mode(11.2).orElseThrow();

        assertEquals(10, mode.index());
        assertEquals(10.0, mode.lower());
        assertEquals(11.0, mode.upper());
        assertEquals(10.5, mode.centre());
        assertEquals(3, mode.count());
        assertEquals(5, histogram.sampleCount());
    }

    @Test
    void tiesGoToTheBinClosestToTheLatestValue() {
        Histogram histogram = new Histogram(1.0, 0.0, 100);
        histogram.add(10.5);
        histogram.add(20.5);

        assertEquals(20, histogram.mode(19.0).orElseThrow().index());
        assertEquals(10, histogram.mode(12.0).orElseThrow().index());
        assertEquals(10, histogram.mode(15.5).orElseThrow().index(), "equal distance: lower bin");
    }

    @Test
    void histogramForgetsValuesOutsideItsWindow() {
        Histogram histogram = new Histogram(1.0, 0.0, 3);
        histogram.add(1.5);
        histogram.add(1.5);
        histogram.add(5.5);
        histogram.add(5.5);

        assertEquals(5, histogram.mode(0).orElseThrow().index());
        assertEquals(3, histogram.sampleCount());
    }

    @Test
    void negativeValuesAndOriginAreBinnedCorrectly() {
        Histogram histogram = new Histogram(0.5, 0.25, 10);

        assertEquals(-1, histogram.indexOf(0.0));
        assertEquals(0, histogram.indexOf(0.25));
        assertEquals(-3, histogram.indexOf(-1.0));
        assertTrue(histogram.mode(0).isEmpty());
    }

    @Test
    void binBoundsHaveNoFloatingPointNoise() {
        Histogram histogram = new Histogram(0.41, 0.0, 10);
        histogram.add(23.0);

        Histogram.Bin bin = histogram.mode(23.0).orElseThrow();

        assertEquals(22.96, bin.lower());
        assertEquals(23.37, bin.upper());
    }

    @Test
    void predictorReportsOnlyChangesOfTheWinningBin() {
        HistogramPredictor predictor = new HistogramPredictor(List.of(new HistogramConfig(CO2, 50, 0, 4)));

        PredictionChange first = predictor.update(CO2, Unit.PPM, 610).orElseThrow();
        assertNull(first.previous());
        assertEquals(600, first.current().binLower());
        assertEquals(650, first.current().binUpper());
        assertEquals(625, first.current().mostLikelyValue());
        assertEquals(1.0, first.current().confidence());

        assertTrue(predictor.update(CO2, Unit.PPM, 620).isEmpty(), "same bin, no change");
        assertTrue(predictor.update(CO2, Unit.PPM, 810).isEmpty(), "600 bin still fullest");
        assertEquals(2.0 / 3, predictor.current(CO2).orElseThrow().confidence(), 1e-12, "current prediction is updated");

        PredictionChange moved = predictor.update(CO2, Unit.PPM, 820).orElseThrow();
        assertEquals(600, moved.previous().binLower());
        assertEquals(800, moved.current().binLower(), "tie goes to the bin of the latest value");
    }

    @Test
    void unconfiguredDataTypesAreNotPredicted() {
        HistogramPredictor predictor = new HistogramPredictor(List.of(new HistogramConfig(CO2, 50, 0, 4)));

        assertEquals(Optional.empty(), predictor.update(TEMPERATURE, Unit.CELSIUS, 20));
        assertTrue(predictor.current(TEMPERATURE).isEmpty());
    }

    @Test
    void currentAllListsPredictionsByDataType() {
        HistogramPredictor predictor = new HistogramPredictor(List.of(
                new HistogramConfig(TEMPERATURE, 1, 0, 10), new HistogramConfig(CO2, 50, 0, 10)));
        predictor.update(TEMPERATURE, Unit.CELSIUS, 21.5);
        predictor.update(CO2, Unit.PPM, 600);

        assertEquals(List.of(CO2, TEMPERATURE), predictor.currentAll().stream().map(Prediction::dataType).toList());
    }
}
