package at.ac.univie.dse.iot.msfog.udp;

import java.net.InetSocketAddress;

/**
 * A client that receives aggregates until {@code leaseExpiresAt} (epoch milliseconds).
 *
 * @param subscriptionId id the client uses to renew or cancel
 * @param subscriberId   name the client chose, e.g. its service id; one subscription per name
 * @param callback       where AGGREGATE datagrams are sent
 */
public record FogSubscription(String subscriptionId, String subscriberId, InetSocketAddress callback, long leaseExpiresAt) {

    public boolean isActive(long now) {
        return now < leaseExpiresAt;
    }

    FogSubscription withLease(long newExpiry) {
        return new FogSubscription(subscriptionId, subscriberId, callback, newExpiry);
    }
}
