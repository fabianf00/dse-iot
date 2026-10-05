package at.ac.univie.dse.iot.msml.prediction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Histogram over a sliding window of the most recent {@code maxSamples} values. Bin {@code i}
 * covers {@code [origin + i * binWidth, origin + (i + 1) * binWidth)}. Not thread-safe.
 */
public final class Histogram {

    /** A bin and how many of the recent values fell into it. */
    public record Bin(long index, double lower, double upper, int count) {
        public double centre() {
            return (lower + upper) / 2;
        }
    }

    private final double binWidth;
    private final double origin;
    private final int maxSamples;
    private final Deque<Double> window = new ArrayDeque<>();
    private final Map<Long, Integer> counts = new HashMap<>();

    public Histogram(double binWidth, double origin, int maxSamples) {
        if (binWidth <= 0 || maxSamples <= 0) {
            throw new IllegalArgumentException("binWidth and maxSamples must be > 0");
        }
        this.binWidth = binWidth;
        this.origin = origin;
        this.maxSamples = maxSamples;
    }

    public void add(double value) {
        window.addLast(value);
        counts.merge(indexOf(value), 1, Integer::sum);
        if (window.size() > maxSamples) {
            long evicted = indexOf(window.removeFirst());
            counts.computeIfPresent(evicted, (index, count) -> count == 1 ? null : count - 1);
        }
    }

    /**
     * The bin with the most values. Ties go to the bin whose centre is closest to
     * {@code tieBreakValue} (usually the latest value), then to the lower bin.
     */
    public Optional<Bin> mode(double tieBreakValue) {
        Bin best = null;
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            Bin candidate = bin(entry.getKey(), entry.getValue());
            if (best == null || isBetter(candidate, best, tieBreakValue)) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    public int sampleCount() {
        return window.size();
    }

    long indexOf(double value) {
        return (long) Math.floor((value - origin) / binWidth);
    }

    private Bin bin(long index, int count) {
        return new Bin(index, round(origin + index * binWidth), round(origin + (index + 1) * binWidth), count);
    }

    /** Removes floating-point noise such as 22.959999999999997 from bin bounds. */
    private static double round(double bound) {
        return Math.round(bound * 1e9) / 1e9;
    }

    private static boolean isBetter(Bin candidate, Bin best, double tieBreakValue) {
        if (candidate.count() != best.count()) {
            return candidate.count() > best.count();
        }
        double candidateDistance = Math.abs(candidate.centre() - tieBreakValue);
        double bestDistance = Math.abs(best.centre() - tieBreakValue);
        if (candidateDistance != bestDistance) {
            return candidateDistance < bestDistance;
        }
        return candidate.index() < best.index();
    }
}
