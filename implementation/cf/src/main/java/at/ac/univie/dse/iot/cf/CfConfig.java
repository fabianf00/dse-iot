package at.ac.univie.dse.iot.cf;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import java.time.Duration;
import java.util.List;

/**
 * Configuration of a CF node: the {@code cf} block of every IoT and Fog config.
 *
 * <pre>{@code
 * "cf": {
 *   "nodeId": "iot-temp-1",
 *   "listenPort": 50101,
 *   "scan": { "hosts": ["127.0.0.1"], "portRange": { "from": 50100, "to": 50129 } }
 * }
 * }</pre>
 *
 * <p>Every other field is optional: a missing (or 0) value means the default given in the
 * constant of the same name. Without a {@code scan} block the node is passive: it accepts
 * peers but never searches for any.
 *
 * @param nodeId                unique id of this node, usually the {@code serviceId}
 * @param listenHost            local address to bind, default {@value #DEFAULT_LISTEN_HOST} (all interfaces)
 * @param listenPort            TCP port to accept peers on
 * @param advertisedHost        host other peers reach this node under, sent in the handshake;
 *                              default {@value #DEFAULT_ADVERTISED_HOST}; use the VPN address in a VPN
 * @param scan                  peer discovery settings, {@code null} for a passive node
 * @param maxOutgoingPeers      links this node opens itself, default {@value #DEFAULT_MAX_OUTGOING_PEERS}
 * @param peerRefreshIntervalMs how often the oldest outgoing link is replaced, default {@value #DEFAULT_PEER_REFRESH_INTERVAL_MS}
 * @param heartbeatIntervalMs   PING after this much idle time on a link, default {@value #DEFAULT_HEARTBEAT_INTERVAL_MS}
 * @param heartbeatTimeoutMs    close a link after receiving nothing for this long, default {@value #DEFAULT_HEARTBEAT_TIMEOUT_MS}
 * @param defaultTtl            hops a published message travels, default {@value #DEFAULT_TTL}
 * @param seenCache             duplicate detection settings
 * @param outboundQueueCapacity messages buffered per link before the oldest is dropped, default {@value #DEFAULT_OUTBOUND_QUEUE_CAPACITY}
 * @param maxFrameBytes         maximum size of one message, default {@value #DEFAULT_MAX_FRAME_BYTES}
 */
public record CfConfig(
        NodeId nodeId,
        String listenHost,
        int listenPort,
        String advertisedHost,
        ScanConfig scan,
        int maxOutgoingPeers,
        long peerRefreshIntervalMs,
        long heartbeatIntervalMs,
        long heartbeatTimeoutMs,
        int defaultTtl,
        SeenCacheConfig seenCache,
        int outboundQueueCapacity,
        int maxFrameBytes) implements ValidatableConfig {

    public static final String DEFAULT_LISTEN_HOST = "0.0.0.0";
    public static final String DEFAULT_ADVERTISED_HOST = "127.0.0.1";
    public static final int DEFAULT_MAX_OUTGOING_PEERS = 3;
    public static final long DEFAULT_PEER_REFRESH_INTERVAL_MS = 60_000;
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000;
    public static final long DEFAULT_HEARTBEAT_TIMEOUT_MS = 15_000;
    public static final int DEFAULT_TTL = 6;
    public static final int DEFAULT_OUTBOUND_QUEUE_CAPACITY = 1_000;
    public static final int DEFAULT_MAX_FRAME_BYTES = 64 * 1024;

    public CfConfig {
        listenHost = isBlank(listenHost) ? DEFAULT_LISTEN_HOST : listenHost;
        advertisedHost = isBlank(advertisedHost) ? DEFAULT_ADVERTISED_HOST : advertisedHost;
        maxOutgoingPeers = maxOutgoingPeers == 0 ? DEFAULT_MAX_OUTGOING_PEERS : maxOutgoingPeers;
        peerRefreshIntervalMs = peerRefreshIntervalMs == 0 ? DEFAULT_PEER_REFRESH_INTERVAL_MS : peerRefreshIntervalMs;
        heartbeatIntervalMs = heartbeatIntervalMs == 0 ? DEFAULT_HEARTBEAT_INTERVAL_MS : heartbeatIntervalMs;
        heartbeatTimeoutMs = heartbeatTimeoutMs == 0 ? DEFAULT_HEARTBEAT_TIMEOUT_MS : heartbeatTimeoutMs;
        defaultTtl = defaultTtl == 0 ? DEFAULT_TTL : defaultTtl;
        seenCache = seenCache == null ? new SeenCacheConfig(0, 0) : seenCache;
        outboundQueueCapacity = outboundQueueCapacity == 0 ? DEFAULT_OUTBOUND_QUEUE_CAPACITY : outboundQueueCapacity;
        maxFrameBytes = maxFrameBytes == 0 ? DEFAULT_MAX_FRAME_BYTES : maxFrameBytes;
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.notNull("nodeId", nodeId)
                .port("listenPort", listenPort)
                .check(!"0.0.0.0".equals(advertisedHost), "advertisedHost", "must be an address peers can connect to")
                .positive("maxOutgoingPeers", maxOutgoingPeers)
                .positive("peerRefreshIntervalMs", peerRefreshIntervalMs)
                .positive("heartbeatIntervalMs", heartbeatIntervalMs)
                .check(heartbeatTimeoutMs > heartbeatIntervalMs, "heartbeatTimeoutMs", "must be > heartbeatIntervalMs")
                .positive("defaultTtl", defaultTtl)
                .nested("seenCache", seenCache)
                .positive("outboundQueueCapacity", outboundQueueCapacity)
                .inRange("maxFrameBytes", maxFrameBytes, 1024, 16 * 1024 * 1024);
        if (scan != null) {
            validator.nested("scan", scan);
        }
    }

    /**
     * Returns this config with {@code nodeId} set to {@code fallback} if it has none, so a service
     * config can omit {@code cf.nodeId} and use its {@code serviceId}. An invalid fallback is
     * ignored; validation then reports the missing node id.
     */
    public CfConfig withDefaultNodeId(String fallback) {
        if (nodeId != null || fallback == null) {
            return this;
        }
        NodeId defaultId;
        try {
            defaultId = NodeId.of(fallback);
        } catch (IllegalArgumentException e) {
            return this;
        }
        return new CfConfig(defaultId, listenHost, listenPort, advertisedHost, scan, maxOutgoingPeers,
                peerRefreshIntervalMs, heartbeatIntervalMs, heartbeatTimeoutMs, defaultTtl, seenCache,
                outboundQueueCapacity, maxFrameBytes);
    }

    /** Validates the config on its own (outside of a service config file). */
    void requireValid() {
        ConfigValidator validator = new ConfigValidator();
        validate(validator);
        validator.throwIfInvalid("CF config of " + nodeId);
    }

    public static Builder builder(String nodeId, int listenPort) {
        return new Builder(NodeId.of(nodeId), listenPort);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Peer discovery: random {@code host:port} candidates from {@code hosts} × {@code portRange}.
     *
     * @param hosts                    hosts to scan (IP addresses or names)
     * @param portRange                ports to scan on every host, inclusive
     * @param intervalMs               delay between scan rounds, default {@value #DEFAULT_INTERVAL_MS}
     * @param connectTimeoutMs         TCP connect timeout, default {@value #DEFAULT_CONNECT_TIMEOUT_MS}
     * @param handshakeTimeoutMs       time a candidate has to answer HELLO, default {@value #DEFAULT_HANDSHAKE_TIMEOUT_MS}
     * @param failedCandidateBackoffMs a candidate without a usable peer is skipped this long, default {@value #DEFAULT_FAILED_CANDIDATE_BACKOFF_MS}
     * @param maxAttemptsPerRound      connection attempts per scan round, default {@value #DEFAULT_MAX_ATTEMPTS_PER_ROUND}
     */
    public record ScanConfig(
            List<String> hosts,
            PortRange portRange,
            long intervalMs,
            long connectTimeoutMs,
            long handshakeTimeoutMs,
            long failedCandidateBackoffMs,
            int maxAttemptsPerRound) implements ValidatableConfig {

        public static final long DEFAULT_INTERVAL_MS = 500;
        public static final long DEFAULT_CONNECT_TIMEOUT_MS = 300;
        public static final long DEFAULT_HANDSHAKE_TIMEOUT_MS = 1_000;
        public static final long DEFAULT_FAILED_CANDIDATE_BACKOFF_MS = 10_000;
        public static final int DEFAULT_MAX_ATTEMPTS_PER_ROUND = 5;

        public ScanConfig {
            hosts = hosts == null ? List.of() : List.copyOf(hosts);
            intervalMs = intervalMs == 0 ? DEFAULT_INTERVAL_MS : intervalMs;
            connectTimeoutMs = connectTimeoutMs == 0 ? DEFAULT_CONNECT_TIMEOUT_MS : connectTimeoutMs;
            handshakeTimeoutMs = handshakeTimeoutMs == 0 ? DEFAULT_HANDSHAKE_TIMEOUT_MS : handshakeTimeoutMs;
            failedCandidateBackoffMs = failedCandidateBackoffMs == 0
                    ? DEFAULT_FAILED_CANDIDATE_BACKOFF_MS : failedCandidateBackoffMs;
            maxAttemptsPerRound = maxAttemptsPerRound == 0 ? DEFAULT_MAX_ATTEMPTS_PER_ROUND : maxAttemptsPerRound;
        }

        public ScanConfig(List<String> hosts, PortRange portRange) {
            this(hosts, portRange, 0, 0, 0, 0, 0);
        }

        @Override
        public void validate(ConfigValidator validator) {
            validator.notEmpty("hosts", hosts);
            for (int i = 0; i < hosts.size(); i++) {
                validator.notBlank("hosts[" + i + "]", hosts.get(i));
            }
            validator.nested("portRange", portRange)
                    .positive("intervalMs", intervalMs)
                    .positive("connectTimeoutMs", connectTimeoutMs)
                    .positive("handshakeTimeoutMs", handshakeTimeoutMs)
                    .positive("failedCandidateBackoffMs", failedCandidateBackoffMs)
                    .positive("maxAttemptsPerRound", maxAttemptsPerRound);
        }
    }

    /** Inclusive port range. */
    public record PortRange(int from, int to) implements ValidatableConfig {

        @Override
        public void validate(ConfigValidator validator) {
            validator.port("from", from).port("to", to).check(from <= to, "to", "must be >= from");
        }
    }

    /**
     * Duplicate detection: ids of seen messages are remembered for {@code expiryMs}, at most
     * {@code maxEntries} of them.
     */
    public record SeenCacheConfig(long expiryMs, int maxEntries) implements ValidatableConfig {

        public static final long DEFAULT_EXPIRY_MS = 60_000;
        public static final int DEFAULT_MAX_ENTRIES = 20_000;

        public SeenCacheConfig {
            expiryMs = expiryMs == 0 ? DEFAULT_EXPIRY_MS : expiryMs;
            maxEntries = maxEntries == 0 ? DEFAULT_MAX_ENTRIES : maxEntries;
        }

        @Override
        public void validate(ConfigValidator validator) {
            validator.positive("expiryMs", expiryMs).positive("maxEntries", maxEntries);
        }
    }

    /** Builds a config in code, mainly for tests. Unset values use the defaults. */
    public static final class Builder {

        private final NodeId nodeId;
        private final int listenPort;
        private String listenHost;
        private String advertisedHost;
        private ScanConfig scan;
        private int maxOutgoingPeers;
        private long peerRefreshIntervalMs;
        private long heartbeatIntervalMs;
        private long heartbeatTimeoutMs;
        private int defaultTtl;
        private SeenCacheConfig seenCache;
        private int outboundQueueCapacity;
        private int maxFrameBytes;

        private Builder(NodeId nodeId, int listenPort) {
            this.nodeId = nodeId;
            this.listenPort = listenPort;
        }

        public Builder listenHost(String host) {
            this.listenHost = host;
            return this;
        }

        public Builder advertisedHost(String host) {
            this.advertisedHost = host;
            return this;
        }

        public Builder scan(ScanConfig scan) {
            this.scan = scan;
            return this;
        }

        public Builder maxOutgoingPeers(int peers) {
            this.maxOutgoingPeers = peers;
            return this;
        }

        public Builder peerRefreshInterval(Duration interval) {
            this.peerRefreshIntervalMs = interval.toMillis();
            return this;
        }

        public Builder heartbeat(Duration interval, Duration timeout) {
            this.heartbeatIntervalMs = interval.toMillis();
            this.heartbeatTimeoutMs = timeout.toMillis();
            return this;
        }

        public Builder defaultTtl(int ttl) {
            this.defaultTtl = ttl;
            return this;
        }

        public Builder seenCache(SeenCacheConfig seenCache) {
            this.seenCache = seenCache;
            return this;
        }

        public Builder outboundQueueCapacity(int capacity) {
            this.outboundQueueCapacity = capacity;
            return this;
        }

        public Builder maxFrameBytes(int bytes) {
            this.maxFrameBytes = bytes;
            return this;
        }

        public CfConfig build() {
            return new CfConfig(nodeId, listenHost, listenPort, advertisedHost, scan, maxOutgoingPeers,
                    peerRefreshIntervalMs, heartbeatIntervalMs, heartbeatTimeoutMs, defaultTtl, seenCache,
                    outboundQueueCapacity, maxFrameBytes);
        }
    }
}
