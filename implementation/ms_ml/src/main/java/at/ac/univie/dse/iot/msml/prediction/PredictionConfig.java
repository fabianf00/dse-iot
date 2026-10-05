package at.ac.univie.dse.iot.msml.prediction;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import java.util.List;

/** One histogram per data type that should be predicted. */
public record PredictionConfig(List<HistogramConfig> histograms) implements ValidatableConfig {

    public PredictionConfig {
        histograms = histograms == null ? null : List.copyOf(histograms);
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notEmpty("histograms", histograms).each("histograms", histograms);
        if (histograms != null) {
            validator.check(histograms.stream().map(HistogramConfig::dataType).distinct().count() == histograms.size(),
                    "histograms", "must not contain a data type twice");
        }
    }
}
