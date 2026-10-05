package at.ac.univie.dse.iot.cf;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Topic subscriptions of the local microservice. Thread-safe; optimized for reads. */
final class SubscriptionRegistry {

    private record Entry(String pattern, MessageListener listener) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    Subscription add(String pattern, MessageListener listener) {
        TopicMatcher.requireValidPattern(pattern);
        Objects.requireNonNull(listener, "listener");
        // Identity, not equals: the same listener may be registered twice and cancelled separately.
        Entry entry = new Entry(pattern, listener);
        entries.add(entry);
        return () -> entries.removeIf(existing -> existing == entry);
    }

    List<MessageListener> matching(String topic) {
        return entries.stream()
                .filter(entry -> TopicMatcher.matches(entry.pattern(), topic))
                .map(Entry::listener)
                .toList();
    }
}
