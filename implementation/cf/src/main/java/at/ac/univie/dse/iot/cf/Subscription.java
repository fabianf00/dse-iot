package at.ac.univie.dse.iot.cf;

/** Handle of a registered {@link MessageListener}. */
@FunctionalInterface
public interface Subscription {

    /** Stops delivering messages to the listener. Calling it again is harmless. */
    void cancel();
}
