package at.ac.univie.dse.iot.msml.jobs;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded queue between producers (UDP receiver, timers, jobs) and the workers. Submitting never
 * blocks: if the queue is full the job is dropped and counted, so a flood of datagrams cannot
 * stall the receiver.
 */
public final class JobQueue {

    private static final Logger LOG = LoggerFactory.getLogger(JobQueue.class);

    private final BlockingQueue<Job> queue;
    private final AtomicLong dropped = new AtomicLong();

    public JobQueue(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Queues {@code job}; returns {@code false} and drops it if the queue is full. */
    public boolean submit(Job job) {
        if (queue.offer(job)) {
            return true;
        }
        long count = dropped.incrementAndGet();
        if (count == 1 || count % 100 == 0) {
            LOG.warn("Job queue full, dropped {} ({} dropped so far)", job.name(), count);
        }
        return false;
    }

    Job take() throws InterruptedException {
        return queue.take();
    }

    public int size() {
        return queue.size();
    }

    public long droppedCount() {
        return dropped.get();
    }
}
