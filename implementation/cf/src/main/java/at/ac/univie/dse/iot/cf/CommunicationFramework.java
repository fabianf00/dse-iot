package at.ac.univie.dse.iot.cf;

import java.util.Set;

/**
 * Facade of the Communication Framework: everything a microservice needs to exchange messages
 * with its peers. Sockets, peer discovery, forwarding and threads stay hidden.
 *
 * <pre>{@code
 * try (CommunicationFramework cf = CommunicationFrameworkFactory.create(config.cf())) {
 *     cf.subscribe("sensor.temperature", message -> collector.add(message.payloadAs(SensorReading.class)));
 *     cf.start();
 *     cf.publish("sensor.temperature", reading);
 * }
 * }</pre>
 *
 * <p>All methods are thread-safe. {@link #publish} never blocks on the network, and listeners are
 * called on a CF thread, never on the publishing thread.
 */
public interface CommunicationFramework extends AutoCloseable {

    /**
     * Binds the listen port and starts accepting peers, scanning for peers and the timers.
     *
     * @throws CfException           if the listen port cannot be bound
     * @throws IllegalStateException if the CF was started or closed before
     */
    void start();

    /**
     * Sends {@code payload} to all peers in the overlay. Returns immediately; delivery is best
     * effort. If the node has no peers yet, the message is dropped.
     *
     * @param topic   topic, dot-separated segments of {@code [A-Za-z0-9_-]}, e.g. {@code sensor.temperature}
     * @param payload any object that can be converted to JSON, or a {@code JsonNode}
     * @throws IllegalArgumentException if the topic is invalid
     * @throws CfException              if the payload is too large for one frame
     * @throws IllegalStateException    if the CF is not running
     */
    void publish(String topic, Object payload);

    /**
     * Registers {@code listener} for DATA messages whose topic matches {@code topicPattern}:
     * an exact topic, {@code <prefix>.*} or {@code *}. Messages published by this node itself
     * are not delivered to it. Listeners are called one at a time on the CF dispatcher thread;
     * a slow listener delays other listeners but never the network.
     *
     * @throws IllegalArgumentException if the pattern is invalid
     */
    Subscription subscribe(String topicPattern, MessageListener listener);

    NodeId nodeId();

    /** Snapshot of the currently connected peers. */
    Set<PeerInfo> connectedPeers();

    /** Says goodbye to all peers and releases all sockets and threads. Calling it again is harmless. */
    @Override
    void close();
}
