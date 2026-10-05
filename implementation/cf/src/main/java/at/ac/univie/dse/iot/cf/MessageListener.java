package at.ac.univie.dse.iot.cf;

/** Receives DATA messages of a subscription. Exceptions thrown by a listener are logged and ignored. */
@FunctionalInterface
public interface MessageListener {

    void onMessage(CfMessage message);
}
