package at.ac.univie.dse.iot.msml.anomaly;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.msml.data.DataPoint;
import at.ac.univie.dse.iot.msml.data.TimeSeriesStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anomaly detection with behaviour chains (P ↦ C ↦ F).
 *
 * <p>For every chain:
 * <ol>
 *   <li>If past and current phase hold and no expectation is open, an expectation is opened that
 *       lasts {@code future.withinSec}.</li>
 *   <li>Every value of the future data type received after the expectation was opened is checked.
 *       The first value that breaks the future condition is an anomaly and closes the expectation.</li>
 *   <li>At the deadline the expectation closes: fulfilled if values were seen, "insufficient data"
 *       otherwise (logged, not an anomaly).</li>
 * </ol>
 * One expectation per chain at a time: an anomaly in the current phase also prevents a new
 * expectation until the phase holds again, so one incident is reported once, not with every value.
 *
 * <p>Every chain has its own lock; chains are evaluated in parallel by different workers.
 */
public final class AnomalyDetector {

    private static final Logger LOG = LoggerFactory.getLogger(AnomalyDetector.class);

    private static final class ChainState {
        final BehaviourChain chain;
        final ReentrantLock lock = new ReentrantLock();
        Expectation open;

        ChainState(BehaviourChain chain) {
            this.chain = chain;
        }
    }

    private final List<ChainState> chains;
    private final int minSamplesPerPhase;
    private final TimeSeriesStore store;

    public AnomalyDetector(List<BehaviourChain> chains, int minSamplesPerPhase, TimeSeriesStore store) {
        this.chains = chains.stream().map(ChainState::new).toList();
        this.minSamplesPerPhase = minSamplesPerPhase;
        this.store = store;
    }

    /** Evaluates the chains a new value of {@code dataType} can affect. */
    public List<Anomaly> evaluate(DataType dataType, Instant now) {
        List<Anomaly> anomalies = new ArrayList<>();
        for (ChainState state : chains) {
            if (state.chain.involves(dataType)) {
                evaluate(state, now).ifPresent(anomalies::add);
            }
        }
        return anomalies;
    }

    /** Evaluates all chains; closes expectations whose deadline passed. Called periodically. */
    public List<Anomaly> evaluateAll(Instant now) {
        List<Anomaly> anomalies = new ArrayList<>();
        for (ChainState state : chains) {
            evaluate(state, now).ifPresent(anomalies::add);
        }
        return anomalies;
    }

    /** The open expectation of a chain, if any. */
    public Optional<Expectation> openExpectation(String chainId) {
        return chains.stream().filter(s -> s.chain.id().equals(chainId)).findFirst().flatMap(state -> {
            state.lock.lock();
            try {
                return Optional.ofNullable(state.open);
            } finally {
                state.lock.unlock();
            }
        });
    }

    private Optional<Anomaly> evaluate(ChainState state, Instant now) {
        state.lock.lock();
        try {
            if (state.open != null) {
                Optional<Anomaly> anomaly = checkExpectation(state, now);
                if (anomaly.isPresent() || state.open != null) {
                    return anomaly;
                }
            }
            openExpectationIfPhasesHold(state, now);
            return Optional.empty();
        } finally {
            state.lock.unlock();
        }
    }

    /** Checks the open expectation; closes it on an anomaly or when its deadline has passed. */
    private Optional<Anomaly> checkExpectation(ChainState state, Instant now) {
        Expectation expectation = state.open;
        FuturePhase future = expectation.chain().future();
        Instant until = now.isBefore(expectation.deadline()) ? now : expectation.deadline();
        List<DataPoint> values = store.range(future.dataType(), expectation.createdAt(), until).stream()
                .filter(point -> point.at().isAfter(expectation.createdAt()))
                .toList();
        for (DataPoint point : values) {
            if (!future.condition().test(point.value())) {
                state.open = null;
                Anomaly anomaly = new Anomaly(expectation, point.value(), point.at(),
                        store.unitOf(future.dataType()).orElse(null));
                LOG.warn("Anomaly in chain {}: expected {}, observed {}", expectation.chain().id(),
                        anomaly.expected(), point.value());
                return Optional.of(anomaly);
            }
        }
        if (!now.isBefore(expectation.deadline())) {
            state.open = null;
            if (values.isEmpty()) {
                LOG.info("Expectation of chain {} closed with insufficient data: no {} values until {}",
                        expectation.chain().id(), future.dataType(), expectation.deadline());
            } else {
                LOG.debug("Expectation of chain {} fulfilled by {} values", expectation.chain().id(), values.size());
            }
        }
        return Optional.empty();
    }

    private void openExpectationIfPhasesHold(ChainState state, Instant now) {
        BehaviourChain chain = state.chain;
        Optional<PhaseObservation> past = observe(chain.past(), now);
        if (past.isEmpty()) {
            return;
        }
        Optional<PhaseObservation> current = observe(chain.current(), now);
        if (current.isEmpty()) {
            return;
        }
        state.open = new Expectation(chain, now, now.plus(chain.future().within()), past.get(), current.get());
        LOG.debug("Chain {}: past and current phase hold, expecting {} until {}", chain.id(),
                chain.future().condition().describe(chain.future().dataType()), state.open.deadline());
    }

    /** The phase's observation if it holds at {@code now}. */
    private Optional<PhaseObservation> observe(Phase phase, Instant now) {
        Instant from = phase.startAt(now);
        Instant to = phase.endAt(now);
        List<DataPoint> values = store.range(phase.dataType(), from, to);
        if (values.size() < minSamplesPerPhase) {
            return Optional.empty();
        }
        boolean holds = values.stream().allMatch(point -> phase.condition().test(point.value()));
        return holds
                ? Optional.of(new PhaseObservation(phase.condition().describe(phase.dataType()), from, to, values.size()))
                : Optional.empty();
    }
}
