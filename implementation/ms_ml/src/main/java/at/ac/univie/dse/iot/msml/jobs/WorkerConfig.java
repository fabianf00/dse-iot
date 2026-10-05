package at.ac.univie.dse.iot.msml.jobs;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;

/**
 * Job/worker settings.
 *
 * @param threads       worker threads, default {@value #DEFAULT_THREADS}
 * @param queueCapacity jobs that may wait, default {@value #DEFAULT_QUEUE_CAPACITY}
 */
public record WorkerConfig(int threads, int queueCapacity) implements ValidatableConfig {

    public static final int DEFAULT_THREADS = 4;
    public static final int DEFAULT_QUEUE_CAPACITY = 1_000;

    public WorkerConfig {
        threads = threads == 0 ? DEFAULT_THREADS : threads;
        queueCapacity = queueCapacity == 0 ? DEFAULT_QUEUE_CAPACITY : queueCapacity;
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.positive("threads", threads).positive("queueCapacity", queueCapacity);
    }
}
