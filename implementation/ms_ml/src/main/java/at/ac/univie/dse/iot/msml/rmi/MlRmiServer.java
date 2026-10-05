package at.ac.univie.dse.iot.msml.rmi;

import at.ac.univie.dse.iot.mlapi.MlRemoteService;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes the ML Service API: creates an RMI registry, exports the service on a fixed port and
 * binds it under its name.
 */
public final class MlRmiServer {

    private static final Logger LOG = LoggerFactory.getLogger(MlRmiServer.class);
    private static final String HOSTNAME_PROPERTY = "java.rmi.server.hostname";

    private final RmiConfig config;
    private final MlRemoteService service;
    private Registry registry;
    private boolean exported;

    public MlRmiServer(RmiConfig config, MlRemoteService service) {
        this.config = config;
        this.service = service;
    }

    /**
     * Starts the registry and binds the service.
     *
     * @throws RemoteException if a port is in use
     */
    public synchronized void start() throws RemoteException {
        // Must be set before the first remote object is exported; a command line value wins.
        if (System.getProperty(HOSTNAME_PROPERTY) == null) {
            System.setProperty(HOSTNAME_PROPERTY, config.hostname());
        }
        registry = LocateRegistry.createRegistry(config.registryPort());
        MlRemoteService stub = (MlRemoteService) UnicastRemoteObject.exportObject(service, config.objectPort());
        exported = true;
        registry.rebind(config.bindName(), stub);
        LOG.info("ML Service API available at rmi://{}:{}/{} (object port {})", System.getProperty(HOSTNAME_PROPERTY),
                config.registryPort(), config.bindName(), config.objectPort() == 0 ? "random" : config.objectPort());
    }

    public synchronized void stop() {
        if (registry != null) {
            try {
                registry.unbind(config.bindName());
            } catch (RemoteException | NotBoundException e) {
                LOG.debug("Unbinding failed: {}", e.getMessage());
            }
        }
        if (exported) {
            unexport(service);
            exported = false;
        }
        if (registry != null) {
            unexport(registry);
            registry = null;
        }
    }

    private static void unexport(Remote remote) {
        try {
            UnicastRemoteObject.unexportObject(remote, true);
        } catch (NoSuchObjectException e) {
            // Already gone.
        }
    }
}
