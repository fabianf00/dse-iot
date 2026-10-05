package at.ac.univie.dse.iot.testsupport;

import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/**
 * Exports a remote object with its own RMI registry on a fixed port, and can stop and start it
 * again on the same port (e.g. to simulate a restarted ML service).
 */
public final class RmiTestServer implements AutoCloseable {

    private final int registryPort;
    private Registry registry;
    private Remote exported;

    public RmiTestServer(int registryPort) {
        this.registryPort = registryPort;
    }

    /** Creates the registry and binds {@code service} under {@code bindName}. */
    public synchronized void start(Remote service, String bindName) throws RemoteException {
        registry = LocateRegistry.createRegistry(registryPort);
        Remote stub = UnicastRemoteObject.exportObject(service, 0);
        exported = service;
        registry.rebind(bindName, stub);
    }

    /** Unexports the service and the registry; calls on old stubs fail from now on. */
    public synchronized void stop() {
        unexport(exported);
        unexport(registry);
        exported = null;
        registry = null;
    }

    public int registryPort() {
        return registryPort;
    }

    @Override
    public void close() {
        stop();
    }

    private static void unexport(Remote remote) {
        if (remote == null) {
            return;
        }
        try {
            UnicastRemoteObject.unexportObject(remote, true);
        } catch (NoSuchObjectException e) {
            // Already stopped.
        }
    }
}
