package at.ac.univie.dse.iot.cf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests of the routing building blocks: router, duplicate cache and topic matching. */
class RoutingTest {

    private static final NodeId SELF = NodeId.of("self");
    private static final NodeId NEIGHBOUR = NodeId.of("neighbour");
    private static final NodeId ORIGIN = NodeId.of("origin");

    private record Sent(CfMessage message, NodeId except) {
    }

    private final AtomicLong now = new AtomicLong(1_000);
    private final List<Sent> sent = new ArrayList<>();
    private final List<CfMessage> delivered = new ArrayList<>();
    private final MessageRouter router = new MessageRouter(SELF, 3, new SeenMessageCache(10_000, 100, now::get),
            (message, except) -> sent.add(new Sent(message, except)), delivered::add, now::get);

    private static CfMessage dataFrom(NodeId origin, int ttl) {
        return CfMessage.data(origin, "test.topic", JsonNodeFactory.instance.objectNode(), ttl, 1);
    }

    @Test
    void publishSendsToAllPeersWithDefaultTtlAndIsNotDeliveredLocally() {
        CfMessage published = router.publish("test.topic", JsonNodeFactory.instance.objectNode());

        assertEquals(1, sent.size());
        assertNull(sent.get(0).except());
        assertEquals(3, sent.get(0).message().ttl());
        assertEquals(SELF, published.origin());
        assertTrue(delivered.isEmpty());
    }

    @Test
    void receivedMessageIsDeliveredAndForwardedExceptToTheSender() {
        CfMessage message = dataFrom(ORIGIN, 3);

        router.onData(message, NEIGHBOUR);

        assertEquals(List.of(message), delivered);
        assertEquals(1, sent.size());
        assertEquals(NEIGHBOUR, sent.get(0).except());
        assertEquals(2, sent.get(0).message().ttl());
        assertEquals(SELF, sent.get(0).message().sender());
    }

    @Test
    void messageWithTtlOneIsDeliveredButNotForwarded() {
        router.onData(dataFrom(ORIGIN, 1), NEIGHBOUR);

        assertEquals(1, delivered.size());
        assertTrue(sent.isEmpty());
    }

    @Test
    void duplicateIsNeitherDeliveredNorForwarded() {
        CfMessage message = dataFrom(ORIGIN, 3);

        router.onData(message, NEIGHBOUR);
        router.onData(message.forwardedBy(NodeId.of("other")), NodeId.of("other"));

        assertEquals(1, delivered.size());
        assertEquals(1, sent.size());
        assertEquals(1, router.duplicatesDropped());
    }

    @Test
    void ownMessageComingBackIsIgnored() {
        router.onData(dataFrom(SELF, 3), NEIGHBOUR);

        assertTrue(delivered.isEmpty());
        assertTrue(sent.isEmpty());
    }

    @Test
    void ownPublishedMessageIsRecognizedAsDuplicate() {
        CfMessage published = router.publish("test.topic", JsonNodeFactory.instance.objectNode());
        sent.clear();

        router.onData(published.forwardedBy(NEIGHBOUR), NEIGHBOUR);

        assertTrue(delivered.isEmpty());
        assertTrue(sent.isEmpty());
    }

    @Test
    void seenCacheForgetsIdsAfterExpiry() {
        SeenMessageCache cache = new SeenMessageCache(100, 10, now::get);
        UUID id = UUID.randomUUID();

        assertTrue(cache.markIfNew(id));
        assertFalse(cache.markIfNew(id));
        now.addAndGet(100);
        assertTrue(cache.markIfNew(id));
    }

    @Test
    void seenCacheIsBoundedAndForgetsOldestFirst() {
        SeenMessageCache cache = new SeenMessageCache(60_000, 2, now::get);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        cache.markIfNew(first);
        cache.markIfNew(second);
        cache.markIfNew(third);

        assertEquals(2, cache.size());
        assertFalse(cache.markIfNew(third));
        assertTrue(cache.markIfNew(first));
    }

    @Test
    void evictExpiredRemovesOnlyExpiredIds() {
        SeenMessageCache cache = new SeenMessageCache(100, 10, now::get);
        cache.markIfNew(UUID.randomUUID());
        now.addAndGet(50);
        cache.markIfNew(UUID.randomUUID());
        now.addAndGet(60);

        cache.evictExpired();

        assertEquals(1, cache.size());
    }

    @ParameterizedTest
    @CsvSource({
            "sensor.temperature, sensor.temperature, true",
            "sensor.temperature, sensor.co2, false",
            "sensor.*, sensor.temperature, true",
            "sensor.*, sensor.temperature.raw, true",
            "sensor.*, sensor, false",
            "sensor.*, sensorx.temperature, false",
            "*, anything.at.all, true"})
    void topicPatternsMatch(String pattern, String topic, boolean expected) {
        assertEquals(expected, TopicMatcher.matches(pattern, topic));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a..b", ".a", "a.", "a b", "a.*.b", "sensor*", "a.**"})
    void invalidPatternsAreRejected(String pattern) {
        assertThrows(IllegalArgumentException.class, () -> TopicMatcher.requireValidPattern(pattern));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a..b", "a.*", "*", "with space"})
    void invalidTopicsAreRejected(String topic) {
        assertThrows(IllegalArgumentException.class, () -> TopicMatcher.requireValidTopic(topic));
    }

    @Test
    void subscriptionsCanBeCancelledIndividually() {
        SubscriptionRegistry registry = new SubscriptionRegistry();
        MessageListener listener = message -> { };
        Subscription first = registry.add("sensor.*", listener);
        registry.add("sensor.temperature", listener);

        assertEquals(2, registry.matching("sensor.temperature").size());
        first.cancel();
        first.cancel();
        assertEquals(1, registry.matching("sensor.temperature").size());
        assertEquals(0, registry.matching("sensor.co2").size());
    }
}
