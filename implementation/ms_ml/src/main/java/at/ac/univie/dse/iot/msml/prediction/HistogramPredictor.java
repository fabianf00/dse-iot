package at.ac.univie.dse.iot.msml.prediction;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Predicts each data type as the fullest bin of a histogram over its recent values.
 *
 * <p>Every data type has its own lock: different types update in parallel, updates of one type
 * are serialized.
 */
public final class HistogramPredictor {

    private static final Logger LOG = LoggerFactory.getLogger(HistogramPredictor.class);

    private static final class TypeState {
        final ReentrantLock lock = new ReentrantLock();
        final Histogram histogram;
        volatile Prediction current;
        long currentBin = Long.MIN_VALUE;

        TypeState(HistogramConfig config) {
            histogram = new Histogram(config.binWidth(), config.origin(), config.maxSamples());
        }
    }

    private final Map<DataType, TypeState> states;
    private final Set<DataType> warnedUnknown = ConcurrentHashMap.newKeySet();

    public HistogramPredictor(List<HistogramConfig> histograms) {
        this.states = histograms.stream().collect(Collectors.toUnmodifiableMap(HistogramConfig::dataType,
                TypeState::new));
    }

    /**
     * Adds a value and recomputes the prediction of its type.
     *
     * @return the change if the predicted bin moved (or this is the first prediction)
     */
    public Optional<PredictionChange> update(DataType dataType, Unit unit, double value) {
        TypeState state = states.get(dataType);
        if (state == null) {
            if (warnedUnknown.add(dataType)) {
                LOG.warn("No histogram configured for {}, it is not predicted", dataType);
            }
            return Optional.empty();
        }
        state.lock.lock();
        try {
            state.histogram.add(value);
            Histogram.Bin bin = state.histogram.mode(value).orElseThrow();
            int samples = state.histogram.sampleCount();
            Prediction prediction = new Prediction(dataType, unit, bin.lower(), bin.upper(), bin.centre(),
                    (double) bin.count() / samples, samples);
            Prediction previous = state.current;
            state.current = prediction;
            if (bin.index() == state.currentBin) {
                return Optional.empty();
            }
            state.currentBin = bin.index();
            LOG.info("Prediction for {} changed to [{}, {}) {}", dataType, bin.lower(), bin.upper(), unit);
            return Optional.of(new PredictionChange(dataType, previous, prediction));
        } finally {
            state.lock.unlock();
        }
    }

    public Optional<Prediction> current(DataType dataType) {
        TypeState state = states.get(dataType);
        return state == null ? Optional.empty() : Optional.ofNullable(state.current);
    }

    /** Current predictions of all data types that have one, ordered by data type. */
    public List<Prediction> currentAll() {
        return states.keySet().stream()
                .map(this::current)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(p -> p.dataType().value()))
                .toList();
    }

    public Set<DataType> dataTypes() {
        return states.keySet();
    }
}
