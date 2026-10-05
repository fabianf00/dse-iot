package at.ac.univie.dse.iot.msfog.aggregation;

import at.ac.univie.dse.iot.common.concurrent.ExecutorUtils;
import at.ac.univie.dse.iot.common.model.AggregatedValue;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes the current window every {@code window}, aggregates it and hands the result on.
 *
 * <p>Empty windows produce an empty aggregate as well. After {@code emptyWindowWarningAfter}
 * empty windows in a row a warning is logged: the devices are gone or the CF overlay is split.
 */
public final class AggregationScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(AggregationScheduler.class);

    private final SensorReadingCollector collector;
    private final AggregationStrategy strategy;
    private final DataType dataType;
    private final Unit unit;
    private final Consumer<AggregatedValue> output;
    private final int emptyWindowWarningAfter;
    private ScheduledExecutorService scheduler;
    private int consecutiveEmptyWindows;

    public AggregationScheduler(SensorReadingCollector collector, AggregationStrategy strategy, DataType dataType,
            Unit unit, Consumer<AggregatedValue> output, int emptyWindowWarningAfter) {
        this.collector = collector;
        this.strategy = strategy;
        this.dataType = dataType;
        this.unit = unit;
        this.output = output;
        this.emptyWindowWarningAfter = emptyWindowWarningAfter;
    }

    public synchronized void start(Duration window, ThreadFactory threadFactory) {
        if (scheduler != null) {
            throw new IllegalStateException("aggregation already started");
        }
        // Discard what arrived before the first full window, so every window has the full length.
        collector.closeWindow();
        scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
        scheduler.scheduleAtFixedRate(this::guardedAggregate, window.toMillis(), window.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        ExecutorUtils.shutdownGracefully(scheduler, Duration.ofSeconds(2));
    }

    /** Closes the current window and publishes its aggregate. */
    AggregatedValue aggregateAndPublish() {
        AggregationWindow.Snapshot window = collector.closeWindow();
        OptionalDouble aggregate = strategy.aggregate(window.values());
        AggregatedValue value = aggregate.isPresent()
                ? new AggregatedValue(dataType, unit, strategy.mode(), window.openedAt(), window.closedAt(),
                        window.values().size(), window.deviceCount(), aggregate.getAsDouble())
                : AggregatedValue.empty(dataType, unit, strategy.mode(), window.openedAt(), window.closedAt());
        trackEmptyWindows(value);
        if (value.isEmpty()) {
            LOG.info("{} window closed without readings", dataType);
        } else {
            LOG.info("{} {} of {} readings from {} devices: {} {}", dataType, strategy.mode(), value.sampleCount(),
                    value.deviceCount(), String.format(Locale.ROOT, "%.2f", value.value()), unit.symbol());
        }
        output.accept(value);
        return value;
    }

    private void guardedAggregate() {
        try {
            aggregateAndPublish();
        } catch (RuntimeException e) {
            LOG.error("Aggregating the window failed", e);
        }
    }

    private void trackEmptyWindows(AggregatedValue value) {
        if (!value.isEmpty()) {
            if (consecutiveEmptyWindows >= emptyWindowWarningAfter) {
                LOG.info("Receiving {} readings again", dataType);
            }
            consecutiveEmptyWindows = 0;
            return;
        }
        consecutiveEmptyWindows++;
        if (consecutiveEmptyWindows == emptyWindowWarningAfter) {
            LOG.warn("No {} readings for {} windows in a row: no devices, or the CF overlay is split",
                    dataType, consecutiveEmptyWindows);
        }
    }
}
