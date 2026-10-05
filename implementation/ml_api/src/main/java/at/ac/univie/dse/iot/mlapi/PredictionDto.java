package at.ac.univie.dse.iot.mlapi;

import java.io.Serializable;

/**
 * Prediction of a data type from its histogram: the bin with the most recent values.
 *
 * @param binLower        lower bound of the bin, inclusive
 * @param binUpper        upper bound of the bin, exclusive
 * @param mostLikelyValue centre of the bin
 * @param confidence      share of the recent values that fell into the bin, 0..1
 * @param sampleCount     number of recent values the histogram holds
 * @param unit            unit of the values, e.g. {@code PPM}
 */
public record PredictionDto(String dataType, double binLower, double binUpper, double mostLikelyValue, double confidence,
        int sampleCount, String unit) implements Serializable {

    private static final long serialVersionUID = 1L;
}
