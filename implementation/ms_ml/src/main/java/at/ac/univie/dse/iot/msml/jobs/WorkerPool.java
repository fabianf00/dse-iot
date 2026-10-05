package at.ac.univie.dse.iot.msml.jobs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixed number of worker threads that take jobs from the {@link JobQueue} and execute them.
 * A failing job is logged and does not affect the worker or other jobs.
 */
public final class WorkerPool {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerPool.class);

    private final JobQueue queue;
    private final int threadCount;
    private final ThreadFactory threadFactory;
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicLong executed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean running;

    public WorkerPool(JobQueue queue, int threadCount, ThreadFactory threadFactory) {
        this.queue = queue;
        this.threadCount = threadCount;
        this.threadFactory = threadFactory;
    }

    public synchronized void start() {
        if (running) {
            throw new IllegalStateException("worker pool already started");
        }
        running = true;
        for (int i = 0; i < threadCount; i++) {
            Thread worker = threadFactory.newThread(this::work);
            workers.add(worker);
            worker.start();
        }
    }

    /** Stops the workers; jobs still queued are discarded. */
    public synchronized void stop(Duration timeout) {
        running = false;
        workers.forEach(Thread::interrupt);
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Thread worker : workers) {
            try {
                worker.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        workers.clear();
    }

    public long executedCount() {
        return executed.get();
    }

    public long failedCount() {
        return failed.get();
    }

    private void work() {
        while (running) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                job.execute();
                executed.incrementAndGet();
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                LOG.error("Job {} failed", job.name(), e);
            }
        }
    }
}
