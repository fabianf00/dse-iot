package at.ac.univie.dse.iot.cf;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Remembers ids of messages this node has already handled, so a message that arrives over
 * several paths of the mesh is delivered and forwarded only once.
 *
 * <p>Ids expire after {@code expiryMillis}. If more than {@code maxEntries} ids are stored, the
 * oldest are forgotten early; TTL still bounds how often such a message can come back.
 */
final class SeenMessageCache {

    /** Id → time it was first seen; insertion order equals time order. */
    private final LinkedHashMap<UUID, Long> firstSeenAt = new LinkedHashMap<>();
    private final long expiryMillis;
    private final int maxEntries;
    private final LongSupplier clock;

    SeenMessageCache(long expiryMillis, int maxEntries, LongSupplier clock) {
        this.expiryMillis = expiryMillis;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /** Records {@code id} and returns {@code true} if it was not seen before. */
    synchronized boolean markIfNew(UUID id) {
        long now = clock.getAsLong();
        evictExpired(now);
        if (firstSeenAt.containsKey(id)) {
            return false;
        }
        firstSeenAt.put(id, now);
        if (firstSeenAt.size() > maxEntries) {
            Iterator<UUID> oldest = firstSeenAt.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    synchronized void evictExpired() {
        evictExpired(clock.getAsLong());
    }

    synchronized int size() {
        return firstSeenAt.size();
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<UUID, Long>> entries = firstSeenAt.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue() + expiryMillis > now) {
                return;
            }
            entries.remove();
        }
    }
}
