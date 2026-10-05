package at.ac.univie.dse.iot.msml.ingest;

import java.net.InetSocketAddress;
import java.util.UUID;

/** Mutable state of the subscription at one fog; guarded by the {@link FogClient}. */
final class FogSubscriptionState {

    final FogEndpoint endpoint;
    final InetSocketAddress address;
    SubscriptionStatus status = SubscriptionStatus.PENDING;
    String subscriptionId;
    /** Epoch of the fog stream currently accepted; 0 until known. */
    long fogEpoch;
    long lastSequence;
    long leaseExpiresAt;
    long nextSubscribeAt;
    long subscribeBackoffMs;
    long lastRenewSentAt;
    long lastAggregateAt;
    long aggregatesReceived;
    long aggregatesDropped;
    UUID pendingRequestId;

    FogSubscriptionState(FogEndpoint endpoint, long initialBackoffMs) {
        this.endpoint = endpoint;
        this.address = new InetSocketAddress(endpoint.host(), endpoint.port());
        this.subscribeBackoffMs = initialBackoffMs;
    }
}
