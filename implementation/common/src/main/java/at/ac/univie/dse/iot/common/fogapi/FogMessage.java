package at.ac.univie.dse.iot.common.fogapi;

import at.ac.univie.dse.iot.common.model.AggregatedValue;
import at.ac.univie.dse.iot.common.model.AggregationMode;
import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;
import java.util.UUID;

/**
 * Messages of the Fog Aggregate API v1: one JSON object per UDP datagram, discriminated by
 * {@code type}. Clients subscribe at a fog node and receive every aggregate as a callback
 * datagram until their lease expires.
 *
 * <p>Every message carries the protocol version {@code v}. Requests carry a {@code requestId}
 * that the answer echoes, so a client can match answers to requests.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = FogMessage.Subscribe.class, name = "SUBSCRIBE"),
    @JsonSubTypes.Type(value = FogMessage.SubscribeAck.class, name = "SUBSCRIBE_ACK"),
    @JsonSubTypes.Type(value = FogMessage.Renew.class, name = "RENEW"),
    @JsonSubTypes.Type(value = FogMessage.RenewAck.class, name = "RENEW_ACK"),
    @JsonSubTypes.Type(value = FogMessage.Unsubscribe.class, name = "UNSUBSCRIBE"),
    @JsonSubTypes.Type(value = FogMessage.UnsubscribeAck.class, name = "UNSUBSCRIBE_ACK"),
    @JsonSubTypes.Type(value = FogMessage.Aggregate.class, name = "AGGREGATE"),
    @JsonSubTypes.Type(value = FogMessage.Error.class, name = "ERROR")
})
public sealed interface FogMessage {

    int VERSION = 1;

    /** Protocol version of the message. */
    int v();

    /**
     * Client → fog: start receiving aggregates. Idempotent per {@code subscriberId}: repeating it
     * (e.g. because the answer was lost) updates the existing subscription.
     *
     * @param callbackHost host to send aggregates to; {@code null} means the request's source address
     * @param leaseMs      requested lease; the fog may shorten it
     */
    record Subscribe(int v, UUID requestId, String subscriberId, String callbackHost, int callbackPort, long leaseMs)
            implements FogMessage {
        public Subscribe {
            Objects.requireNonNull(requestId, "requestId");
            if (subscriberId == null || subscriberId.isBlank()) {
                throw new IllegalArgumentException("subscriberId must not be blank");
            }
            requirePort(callbackPort, "callbackPort");
            requirePositive(leaseMs, "leaseMs");
        }

        public static Subscribe of(String subscriberId, String callbackHost, int callbackPort, long leaseMs) {
            return new Subscribe(VERSION, UUID.randomUUID(), subscriberId, callbackHost, callbackPort, leaseMs);
        }
    }

    /** Fog → client: subscription accepted; describes the stream the client will receive. */
    record SubscribeAck(int v, UUID requestId, String subscriptionId, DataType dataType, Unit unit,
            AggregationMode aggregationMode, long windowMs, long leaseExpiresAt, long fogEpoch) implements FogMessage {
    }

    /** Client → fog: extend the lease; recommended at half the lease time. */
    record Renew(int v, UUID requestId, String subscriptionId, long leaseMs) implements FogMessage {
        public Renew {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(subscriptionId, "subscriptionId");
            requirePositive(leaseMs, "leaseMs");
        }

        public static Renew of(String subscriptionId, long leaseMs) {
            return new Renew(VERSION, UUID.randomUUID(), subscriptionId, leaseMs);
        }
    }

    /** Fog → client: lease extended. */
    record RenewAck(int v, UUID requestId, String subscriptionId, long leaseExpiresAt) implements FogMessage {
    }

    /** Client → fog: stop the subscription now instead of letting the lease expire. */
    record Unsubscribe(int v, UUID requestId, String subscriptionId) implements FogMessage {
        public Unsubscribe {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(subscriptionId, "subscriptionId");
        }

        public static Unsubscribe of(String subscriptionId) {
            return new Unsubscribe(VERSION, UUID.randomUUID(), subscriptionId);
        }
    }

    /** Fog → client: subscription removed. */
    record UnsubscribeAck(int v, UUID requestId, String subscriptionId) implements FogMessage {
    }

    /**
     * Fog → subscriber callback: the aggregate of one window. Sent once per window, also for
     * empty windows ({@code sampleCount 0}, {@code value null}), so it doubles as a heartbeat.
     *
     * @param fogId    id of the sending fog node
     * @param fogEpoch start time of the fog process; changes when the fog restarts
     * @param sequence counts aggregates of this epoch, starting at 1
     */
    record Aggregate(int v, String fogId, long fogEpoch, long sequence, DataType dataType, Unit unit,
            AggregationMode aggregationMode, long windowStart, long windowEnd, int sampleCount, int deviceCount,
            Double value) implements FogMessage {

        public Aggregate {
            // Reuses the consistency checks of AggregatedValue.
            new AggregatedValue(dataType, unit, aggregationMode, windowStart, windowEnd, sampleCount, deviceCount, value);
        }

        public static Aggregate of(String fogId, long fogEpoch, long sequence, AggregatedValue value) {
            return new Aggregate(VERSION, fogId, fogEpoch, sequence, value.dataType(), value.unit(),
                    value.aggregationMode(), value.windowStart(), value.windowEnd(), value.sampleCount(),
                    value.deviceCount(), value.value());
        }

        public AggregatedValue toAggregatedValue() {
            return new AggregatedValue(dataType, unit, aggregationMode, windowStart, windowEnd, sampleCount,
                    deviceCount, value);
        }
    }

    /** Fog → client: a request could not be handled. {@code requestId} is null if it could not be read. */
    record Error(int v, UUID requestId, ErrorCode code, String message) implements FogMessage {
        public static Error of(UUID requestId, ErrorCode code, String message) {
            return new Error(VERSION, requestId, code, message);
        }
    }

    /** Error codes of {@link Error}. */
    enum ErrorCode {
        /** Not valid JSON, unknown type, missing or invalid fields, or too large. */
        MALFORMED_REQUEST,
        /** {@code v} is not a version this fog speaks. */
        UNSUPPORTED_VERSION,
        /** RENEW or UNSUBSCRIBE for a subscription the fog does not know (expired, or the fog restarted). */
        UNKNOWN_SUBSCRIPTION,
        /** The fog already serves its maximum number of subscribers. */
        TOO_MANY_SUBSCRIBERS
    }

    private static void requirePort(int port, String field) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(field + " must be between 1 and 65535 but was " + port);
        }
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be > 0 but was " + value);
        }
    }
}
