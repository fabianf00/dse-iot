package at.ac.univie.dse.iot.msml.prediction;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;

/**
 * The most likely value range of a data type: the fullest bin of its histogram.
 *
 * @param mostLikelyValue centre of the bin
 * @param confidence      share of the recent values in the bin, 0..1
 * @param sampleCount     number of recent values in the histogram
 */
public record Prediction(DataType dataType, Unit unit, double binLower, double binUpper, double mostLikelyValue,
        double confidence, int sampleCount) {
}
