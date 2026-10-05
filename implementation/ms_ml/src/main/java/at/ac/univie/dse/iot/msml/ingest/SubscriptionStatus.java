package at.ac.univie.dse.iot.msml.ingest;

/** State of the subscription at one fog node. */
public enum SubscriptionStatus {
    /** Not subscribed yet; SUBSCRIBE is sent with exponential backoff until it is acknowledged. */
    PENDING,
    /** Subscribed; the lease is renewed at half time. */
    ACTIVE,
    /** The lease ran out without a successful renewal; subscribing again. */
    EXPIRED
}
