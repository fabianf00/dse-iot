package at.ac.univie.dse.iot.msml.ingest;

import at.ac.univie.dse.iot.common.concurrent.ExecutorUtils;
import at.ac.univie.dse.iot.common.concurrent.NamedThreadFactory;
import at.ac.univie.dse.iot.common.fogapi.FogMessage;
import at.ac.univie.dse.iot.common.fogapi.FogMessageCodec;
import at.ac.univie.dse.iot.common.fogapi.FogProtocolException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client side of the Fog Aggregate API: subscribes to every configured fog, keeps the leases
 * alive and hands every new aggregate to the {@link AggregateListener}.
 *
 * <ul>
 *   <li>SUBSCRIBE is repeated with exponential backoff (1 s, 2 s, 4 s … up to the configured
 *       maximum) until it is acknowledged; it is idempotent at the fog.</li>
 *   <li>The lease is renewed at half time. If the fog answers UNKNOWN_SUBSCRIPTION (it restarted
 *       or the lease ran out), or the lease runs out without renewal, the client subscribes again.</li>
 *   <li>Aggregates are accepted per fog only in order: duplicates and older sequence numbers are
 *       dropped, gaps are logged. A higher {@code fogEpoch} means the fog restarted and resets the
 *       sequence; aggregates from an older epoch (late datagrams from before the restart) are
 *       dropped.</li>
 * </ul>
 */
public final class FogClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FogClient.class);

    private static final long TICK_MS = 250;
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long RENEW_RESEND_MS = 1_000;
    private static final int RECEIVE_BUFFER_BYTES = 65_535;

    private final String subscriberId;
    private final FogClientConfig config;
    private final AggregateListener listener;
    private final Clock clock;
    private final FogMessageCodec codec = new FogMessageCodec();
    private final Map<String, FogSubscriptionState> fogs = new LinkedHashMap<>();
    private DatagramSocket socket;
    private Thread receiver;
    private ScheduledExecutorService ticker;

    public FogClient(String subscriberId, List<FogEndpoint> endpoints, FogClientConfig config,
            AggregateListener listener, Clock clock) {
        this.subscriberId = subscriberId;
        this.config = config;
        this.listener = listener;
        this.clock = clock;
        endpoints.forEach(endpoint -> fogs.put(endpoint.id(), new FogSubscriptionState(endpoint, INITIAL_BACKOFF_MS)));
    }

    /** Binds the callback port and starts subscribing. */
    public synchronized void start() throws SocketException {
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(config.bindHost(), config.callbackPort()));
        receiver = new Thread(this::receiveLoop, subscriberId + "-fog-receiver");
        receiver.setDaemon(true);
        receiver.start();
        ticker = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory(subscriberId + "-fog-ticker", true));
        ticker.scheduleWithFixedDelay(this::guardedTick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        LOG.info("Receiving aggregates on UDP {}", socket.getLocalSocketAddress());
    }

    /** Unsubscribes (best effort) and releases the socket. */
    @Override
    public void close() {
        ExecutorUtils.shutdownGracefully(ticker, Duration.ofSeconds(1));
        DatagramSocket current;
        synchronized (this) {
            current = socket;
            if (current == null) {
                return;
            }
            for (FogSubscriptionState fog : fogs.values()) {
                if (fog.status == SubscriptionStatus.ACTIVE) {
                    send(FogMessage.Unsubscribe.of(fog.subscriptionId), fog);
                }
            }
        }
        current.close();
        try {
            receiver.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The local port aggregates arrive on. */
    public synchronized int localPort() {
        return socket.getLocalPort();
    }

    /** Subscription state per fog id, e.g. {@code ACTIVE (12 aggregates, last 3 s ago)}. */
    public synchronized Map<String, String> statusSnapshot() {
        long now = clock.millis();
        Map<String, String> status = new LinkedHashMap<>();
        for (FogSubscriptionState fog : fogs.values()) {
            String detail = fog.lastAggregateAt == 0 ? "no aggregates yet"
                    : fog.aggregatesReceived + " aggregates, last " + (now - fog.lastAggregateAt) / 1000 + " s ago";
            status.put(fog.endpoint.id(), fog.status + " (" + detail + ")");
        }
        return status;
    }

    public synchronized SubscriptionStatus status(String fogId) {
        return fogs.get(fogId).status;
    }

    /** Sends due SUBSCRIBE and RENEW requests. Runs every {@value #TICK_MS} ms. */
    synchronized void tick() {
        long now = clock.millis();
        for (FogSubscriptionState fog : fogs.values()) {
            if (fog.status == SubscriptionStatus.ACTIVE && now >= fog.leaseExpiresAt) {
                LOG.warn("Lease at {} ran out without renewal, subscribing again", fog.endpoint.id());
                fog.status = SubscriptionStatus.EXPIRED;
                fog.nextSubscribeAt = now;
            }
            if (fog.status != SubscriptionStatus.ACTIVE) {
                if (now >= fog.nextSubscribeAt) {
                    FogMessage.Subscribe subscribe = FogMessage.Subscribe.of(subscriberId, config.callbackHost(),
                            config.callbackPort(), config.leaseMs());
                    fog.pendingRequestId = subscribe.requestId();
                    send(subscribe, fog);
                    fog.nextSubscribeAt = now + fog.subscribeBackoffMs;
                    fog.subscribeBackoffMs = Math.min(fog.subscribeBackoffMs * 2, config.subscribeRetryMaxMs());
                }
            } else if (fog.leaseExpiresAt - now < config.leaseMs() / 2 && now - fog.lastRenewSentAt >= RENEW_RESEND_MS) {
                FogMessage.Renew renew = FogMessage.Renew.of(fog.subscriptionId, config.leaseMs());
                fog.pendingRequestId = renew.requestId();
                fog.lastRenewSentAt = now;
                send(renew, fog);
            }
        }
    }

    /** Handles one datagram from {@code source}. */
    synchronized void handle(FogMessage message, InetSocketAddress source) {
        long now = clock.millis();
        if (message instanceof FogMessage.Aggregate aggregate) {
            onAggregate(aggregate, source, now);
        } else if (message instanceof FogMessage.SubscribeAck ack) {
            byRequest(ack.requestId()).ifPresent(fog -> onSubscribed(fog, ack));
        } else if (message instanceof FogMessage.RenewAck ack) {
            byRequest(ack.requestId()).ifPresent(fog -> {
                fog.pendingRequestId = null;
                fog.leaseExpiresAt = ack.leaseExpiresAt();
            });
        } else if (message instanceof FogMessage.Error error) {
            onError(error, source, now);
        }
    }

    private void onSubscribed(FogSubscriptionState fog, FogMessage.SubscribeAck ack) {
        fog.pendingRequestId = null;
        fog.status = SubscriptionStatus.ACTIVE;
        fog.subscriptionId = ack.subscriptionId();
        fog.leaseExpiresAt = ack.leaseExpiresAt();
        fog.subscribeBackoffMs = INITIAL_BACKOFF_MS;
        acceptEpoch(fog, ack.fogEpoch());
        LOG.info("Subscribed to {}: {} {} every {} ms", fog.endpoint.id(), ack.dataType(), ack.aggregationMode(),
                ack.windowMs());
    }

    private void onError(FogMessage.Error error, InetSocketAddress source, long now) {
        Optional<FogSubscriptionState> fog = byRequest(error.requestId());
        if (fog.isPresent() && error.code() == FogMessage.ErrorCode.UNKNOWN_SUBSCRIPTION) {
            LOG.info("{} does not know our subscription (restarted or lease ran out), subscribing again",
                    fog.get().endpoint.id());
            fog.get().status = SubscriptionStatus.PENDING;
            fog.get().nextSubscribeAt = now;
        } else {
            LOG.warn("{} answered {}: {}", fog.map(f -> f.endpoint.id()).orElse(source.toString()), error.code(),
                    error.message());
        }
    }

    private void onAggregate(FogMessage.Aggregate aggregate, InetSocketAddress source, long now) {
        FogSubscriptionState fog = fogs.get(aggregate.fogId());
        if (fog == null) {
            fog = fogs.values().stream().filter(f -> f.address.equals(source)).findFirst().orElse(null);
        }
        if (fog == null) {
            LOG.warn("Ignoring aggregate from unknown fog {} at {}", aggregate.fogId(), source);
            return;
        }
        if (aggregate.fogEpoch() < fog.fogEpoch) {
            fog.aggregatesDropped++;
            LOG.debug("Dropped aggregate {} of an old epoch of {}", aggregate.sequence(), fog.endpoint.id());
            return;
        }
        acceptEpoch(fog, aggregate.fogEpoch());
        if (aggregate.sequence() <= fog.lastSequence) {
            fog.aggregatesDropped++;
            LOG.debug("Dropped duplicate or late aggregate {} of {}", aggregate.sequence(), fog.endpoint.id());
            return;
        }
        if (fog.lastSequence > 0 && aggregate.sequence() > fog.lastSequence + 1) {
            LOG.warn("Missed {} aggregates of {}", aggregate.sequence() - fog.lastSequence - 1, fog.endpoint.id());
        }
        fog.lastSequence = aggregate.sequence();
        fog.lastAggregateAt = now;
        fog.aggregatesReceived++;
        listener.onAggregate(fog.endpoint.id(), aggregate.toAggregatedValue(), clock.instant());
    }

    /** A newer epoch means the fog restarted: its sequence numbers start again. */
    private static void acceptEpoch(FogSubscriptionState fog, long epoch) {
        if (epoch > fog.fogEpoch) {
            if (fog.fogEpoch != 0) {
                LOG.info("{} restarted (new epoch {}), sequence numbers start again", fog.endpoint.id(), epoch);
            }
            fog.fogEpoch = epoch;
            fog.lastSequence = 0;
        }
    }

    private Optional<FogSubscriptionState> byRequest(UUID requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return fogs.values().stream().filter(f -> requestId.equals(f.pendingRequestId)).findFirst();
    }

    private void send(FogMessage message, FogSubscriptionState fog) {
        byte[] datagram = codec.encode(message);
        try {
            socket.send(new DatagramPacket(datagram, datagram.length, fog.address));
        } catch (IOException e) {
            LOG.debug("Sending to {} failed: {}", fog.endpoint.id(), e.getMessage());
        }
    }

    private void guardedTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.error("Subscription maintenance failed", e);
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_BYTES];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    LOG.warn("Receiving failed: {}", e.getMessage());
                }
                continue;
            }
            InetSocketAddress source = (InetSocketAddress) packet.getSocketAddress();
            try {
                handle(codec.decode(packet.getData(), 0, packet.getLength()), source);
            } catch (FogProtocolException e) {
                LOG.warn("Ignoring invalid datagram from {}: {}", source, e.getMessage());
            } catch (RuntimeException e) {
                LOG.error("Handling a datagram from {} failed", source, e);
            }
        }
    }
}
