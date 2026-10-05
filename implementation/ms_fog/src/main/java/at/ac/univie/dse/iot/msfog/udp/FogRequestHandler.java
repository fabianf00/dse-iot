package at.ac.univie.dse.iot.msfog.udp;

import at.ac.univie.dse.iot.common.fogapi.FogMessage;
import at.ac.univie.dse.iot.common.fogapi.FogMessage.ErrorCode;
import at.ac.univie.dse.iot.common.fogapi.FogMessageCodec;
import at.ac.univie.dse.iot.common.fogapi.FogProtocolException;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import java.net.InetSocketAddress;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server side of the Fog Aggregate API: turns one request datagram into one answer.
 * Independent of the socket, so it can be tested without network.
 */
public final class FogRequestHandler {

    private static final Logger LOG = LoggerFactory.getLogger(FogRequestHandler.class);

    /** What this fog serves, sent back in SUBSCRIBE_ACK. */
    public record StreamDescription(DataType dataType, Unit unit, AggregationMode aggregationMode, long windowMs,
            long fogEpoch) {
    }

    private final FogMessageCodec codec;
    private final SubscriptionManager subscriptions;
    private final StreamDescription stream;

    public FogRequestHandler(FogMessageCodec codec, SubscriptionManager subscriptions, StreamDescription stream) {
        this.codec = codec;
        this.subscriptions = subscriptions;
        this.stream = stream;
    }

    /** Handles a request from {@code source} and returns the answer to send back to it. */
    public FogMessage handle(byte[] data, int length, InetSocketAddress source) {
        FogMessage request;
        try {
            request = codec.decode(data, 0, length);
        } catch (FogProtocolException e) {
            LOG.warn("Rejected datagram from {}: {} ({})", source, e.code(), e.getMessage());
            return e.toErrorMessage();
        }
        if (request instanceof FogMessage.Subscribe subscribe) {
            return subscribe(subscribe, source);
        }
        if (request instanceof FogMessage.Renew renew) {
            return renew(renew);
        }
        if (request instanceof FogMessage.Unsubscribe unsubscribe) {
            return unsubscribe(unsubscribe);
        }
        return FogMessage.Error.of(null, ErrorCode.MALFORMED_REQUEST,
                request.getClass().getSimpleName() + " is not a request a fog accepts");
    }

    private FogMessage renew(FogMessage.Renew request) {
        return subscriptions.renew(request.subscriptionId(), request.leaseMs())
                .<FogMessage>map(s -> new FogMessage.RenewAck(FogMessage.VERSION, request.requestId(),
                        s.subscriptionId(), s.leaseExpiresAt()))
                .orElseGet(() -> unknownSubscription(request.requestId(), request.subscriptionId()));
    }

    private FogMessage unsubscribe(FogMessage.Unsubscribe request) {
        return subscriptions.unsubscribe(request.subscriptionId())
                ? new FogMessage.UnsubscribeAck(FogMessage.VERSION, request.requestId(), request.subscriptionId())
                : unknownSubscription(request.requestId(), request.subscriptionId());
    }

    private FogMessage subscribe(FogMessage.Subscribe request, InetSocketAddress source) {
        String host = request.callbackHost() == null || request.callbackHost().isBlank()
                ? source.getAddress().getHostAddress() : request.callbackHost();
        InetSocketAddress callback = new InetSocketAddress(host, request.callbackPort());
        if (callback.isUnresolved()) {
            return FogMessage.Error.of(request.requestId(), ErrorCode.MALFORMED_REQUEST,
                    "callbackHost '" + host + "' cannot be resolved");
        }
        try {
            FogSubscription subscription = subscriptions.subscribe(request.subscriberId(), callback, request.leaseMs());
            return new FogMessage.SubscribeAck(FogMessage.VERSION, request.requestId(), subscription.subscriptionId(),
                    stream.dataType(), stream.unit(), stream.aggregationMode(), stream.windowMs(),
                    subscription.leaseExpiresAt(), stream.fogEpoch());
        } catch (SubscriptionManager.TooManySubscribersException e) {
            return FogMessage.Error.of(request.requestId(), ErrorCode.TOO_MANY_SUBSCRIBERS, e.getMessage());
        }
    }

    private static FogMessage unknownSubscription(UUID requestId, String subscriptionId) {
        return FogMessage.Error.of(requestId, ErrorCode.UNKNOWN_SUBSCRIPTION,
                "subscription " + subscriptionId + " is unknown or expired, subscribe again");
    }
}
