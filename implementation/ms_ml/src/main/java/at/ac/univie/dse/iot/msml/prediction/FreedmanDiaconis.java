package at.ac.univie.dse.iot.msml.prediction;

import java.util.Arrays;

/**
 * Freedman–Diaconis rule for histogram bin widths: {@code h = 2 · IQR · n^(−1/3)}. Used upfront
 * on sample data; the result is put into the config ({@link HistogramConfig#binWidth()}).
 */
public final class FreedmanDiaconis {

    private FreedmanDiaconis() {
    }

    /**
     * @throws IllegalArgumentException with fewer than 2 samples, or if the interquartile range is 0
     */
    public static double binWidth(double[] samples) {
        if (samples.length < 2) {
            throw new IllegalArgumentException("at least 2 samples are needed");
        }
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        double iqr = quantile(sorted, 0.75) - quantile(sorted, 0.25);
        if (iqr <= 0) {
            throw new IllegalArgumentException("interquartile range is 0, the rule is not defined");
        }
        return 2 * iqr / Math.cbrt(sorted.length);
    }

    /** Quantile with linear interpolation between closest ranks (as in R type 7 and NumPy's default). */
    static double quantile(double[] sorted, double p) {
        double position = p * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        return sorted[lower] + (position - lower) * (sorted[upper] - sorted[lower]);
    }
}
