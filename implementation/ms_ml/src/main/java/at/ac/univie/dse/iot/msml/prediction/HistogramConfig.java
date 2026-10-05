package at.ac.univie.dse.iot.msml.prediction;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import at.ac.univie.dse.iot.common.model.DataType;

/**
 * Histogram of one data type.
 *
 * @param binWidth   width of a bin, determined upfront with the Freedman–Diaconis rule
 * @param origin     bins start at {@code origin + k * binWidth}
 * @param maxSamples the histogram holds the most recent values only, default {@value #DEFAULT_MAX_SAMPLES}
 */
public record HistogramConfig(DataType dataType, double binWidth, double origin, int maxSamples)
        implements ValidatableConfig {

    public static final int DEFAULT_MAX_SAMPLES = 500;

    public HistogramConfig {
        maxSamples = maxSamples == 0 ? DEFAULT_MAX_SAMPLES : maxSamples;
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notNull("dataType", dataType)
                .positive("binWidth", binWidth)
                .positive("maxSamples", maxSamples);
    }
}
