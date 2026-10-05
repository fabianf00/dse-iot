package at.ac.univie.dse.iot.msfog.udp;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscriptions with leases. A subscriber that stops renewing (e.g. because it crashed) is
 * dropped once its lease expires, so the fog never keeps sending to dead hosts.
 */
public final class SubscriptionManager {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionManager.class);

    /** Thrown when a new subscriber would exceed the limit. */
    public static final class TooManySubscribersException extends Exception {
        private static final long serialVersionUID = 1L;

        TooManySubscribersException(int limit) {
            super("this fog serves at most " + limit + " subscribers");
        }
    }

    private final long maxLeaseMillis;
    private final int maxSubscribers;
    private final LongSupplier clock;
    /** By subscriberId. */
    private final Map<String, FogSubscription> subscriptions = new LinkedHashMap<>();

    public SubscriptionManager(long maxLeaseMillis, int maxSubscribers, LongSupplier clock) {
        this.maxLeaseMillis = maxLeaseMillis;
        this.maxSubscribers = maxSubscribers;
        this.clock = clock;
    }

    /**
     * Creates a subscription, or updates the subscription of the same subscriber (same id, new
     * callback and lease), so a repeated SUBSCRIBE is harmless. The lease is capped at the maximum.
     */
    public synchronized FogSubscription subscribe(String subscriberId, InetSocketAddress callback, long leaseMillis)
            throws TooManySubscribersException {
        long now = clock.getAsLong();
        removeExpired(now);
        FogSubscription existing = subscriptions.get(subscriberId);
        if (existing == null && subscriptions.size() >= maxSubscribers) {
            throw new TooManySubscribersException(maxSubscribers);
        }
        String id = existing == null ? UUID.randomUUID().toString() : existing.subscriptionId();
        FogSubscription subscription = new FogSubscription(id, subscriberId, callback, now + cap(leaseMillis));
        subscriptions.put(subscriberId, subscription);
        if (existing == null) {
            LOG.info("{} subscribed, callback {}", subscriberId, callback);
        }
        return subscription;
    }

    /** Extends the lease of an active subscription; empty if it is unknown or expired. */
    public synchronized Optional<FogSubscription> renew(String subscriptionId, long leaseMillis) {
        long now = clock.getAsLong();
        removeExpired(now);
        Optional<FogSubscription> existing = findById(subscriptionId);
        existing.ifPresent(s -> subscriptions.put(s.subscriberId(), s.withLease(now + cap(leaseMillis))));
        return existing.map(s -> subscriptions.get(s.subscriberId()));
    }

    /** Removes a subscription; returns {@code false} if it was unknown. */
    public synchronized boolean unsubscribe(String subscriptionId) {
        Optional<FogSubscription> existing = findById(subscriptionId);
        existing.ifPresent(s -> {
            subscriptions.remove(s.subscriberId());
            LOG.info("{} unsubscribed", s.subscriberId());
        });
        return existing.isPresent();
    }

    /** Subscriptions whose lease has not expired. */
    public synchronized List<FogSubscription> active() {
        long now = clock.getAsLong();
        return subscriptions.values().stream().filter(s -> s.isActive(now)).toList();
    }

    /** Drops expired subscriptions. */
    public synchronized void sweepExpired() {
        removeExpired(clock.getAsLong());
    }

    private void removeExpired(long now) {
        List<FogSubscription> expired = new ArrayList<>();
        subscriptions.values().removeIf(s -> !s.isActive(now) && expired.add(s));
        expired.forEach(s -> LOG.info("Lease of {} expired, no more aggregates to {}", s.subscriberId(), s.callback()));
    }

    private Optional<FogSubscription> findById(String subscriptionId) {
        return subscriptions.values().stream().filter(s -> s.subscriptionId().equals(subscriptionId)).findFirst();
    }

    private long cap(long leaseMillis) {
        return Math.min(leaseMillis, maxLeaseMillis);
    }
}
