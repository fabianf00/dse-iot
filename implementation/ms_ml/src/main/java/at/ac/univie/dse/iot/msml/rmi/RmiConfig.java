package at.ac.univie.dse.iot.msml.rmi;

import at.ac.univie.dse.iot.common.config.ConfigValidator;
import at.ac.univie.dse.iot.common.config.ValidatableConfig;
import at.ac.univie.dse.iot.mlapi.MlRemoteService;

/**
 * RMI endpoint of MS ML.
 *
 * @param hostname     host clients use to reach the remote object; becomes
 *                     {@code java.rmi.server.hostname} unless that is set on the command line;
 *                     default {@value #DEFAULT_HOSTNAME}, use the VPN address in a VPN
 * @param registryPort port of the RMI registry this service creates, default {@value #DEFAULT_REGISTRY_PORT}
 * @param objectPort   fixed port of the remote object (instead of a random one), so firewalls and
 *                     the VPN can allow it; 0 for a random port
 * @param bindName     name in the registry, default {@value MlRemoteService#DEFAULT_BIND_NAME}
 */
public record RmiConfig(String hostname, int registryPort, int objectPort, String bindName) implements ValidatableConfig {

    public static final String DEFAULT_HOSTNAME = "127.0.0.1";
    public static final int DEFAULT_REGISTRY_PORT = 1099;

    public RmiConfig {
        hostname = hostname == null || hostname.isBlank() ? DEFAULT_HOSTNAME : hostname;
        registryPort = registryPort == 0 ? DEFAULT_REGISTRY_PORT : registryPort;
        bindName = bindName == null || bindName.isBlank() ? MlRemoteService.DEFAULT_BIND_NAME : bindName;
    }

    @Override
    public void validate(ConfigValidator validator) {
        validator.port("registryPort", registryPort).inRange("objectPort", objectPort, 0, 65535);
    }
}
