package at.ac.univie.dse.iot.cf;

import java.util.Objects;

/** Creates {@link CommunicationFramework} instances. */
public final class CommunicationFrameworkFactory {

    private CommunicationFrameworkFactory() {
    }

    /**
     * Creates a CF node. Nothing runs until {@link CommunicationFramework#start()} is called.
     *
     * @throws at.ac.univie.dse.iot.common.config.ConfigException if the config is invalid
     */
    public static CommunicationFramework create(CfConfig config) {
        Objects.requireNonNull(config, "config");
        config.requireValid();
        return new PeerNode(config);
    }
}
