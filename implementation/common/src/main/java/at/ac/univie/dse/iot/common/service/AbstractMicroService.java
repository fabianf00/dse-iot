package at.ac.univie.dse.iot.common.service;

import at.ac.univie.dse.iot.common.config.ConfigException;
import at.ac.univie.dse.iot.common.config.ConfigLoader;
import at.ac.univie.dse.iot.common.config.ServiceConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common lifecycle of every microservice and client (Template Method).
 *
 * <p>{@link #run(String[])} loads the config file given as the only argument, calls
 * {@link #initialize(ServiceConfig)} and {@link #startComponents()}, registers a shutdown hook
 * and blocks until the service is stopped. Subclasses only implement these hooks and
 * {@link #stopComponents()}.
 *
 * <p>A typical entry point:
 * <pre>{@code
 * public static void main(String[] args) {
 *     System.exit(new IotDeviceService().run(args));
 * }
 * }</pre>
 *
 * <p>Tests can skip the config file and call {@link #start(ServiceConfig)} and {@link #stop()}
 * directly.
 *
 * @param <C> type of the service's root config
 */
public abstract class AbstractMicroService<C extends ServiceConfig> {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILURE = 1;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_CONFIG = 3;

    /** Lifecycle states. A service is started at most once. */
    public enum State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final Class<C> configType;
    private final ConfigLoader configLoader;
    private final CountDownLatch terminated = new CountDownLatch(1);
    private final Object lifecycleLock = new Object();

    private volatile State state = State.NEW;
    private volatile C config;
    private Thread shutdownHook;

    protected AbstractMicroService(Class<C> configType) {
        this(configType, new ConfigLoader());
    }

    protected AbstractMicroService(Class<C> configType, ConfigLoader configLoader) {
        this.configType = Objects.requireNonNull(configType, "configType");
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
    }

    /**
     * Runs the service from the command line and blocks until it stops.
     *
     * @param args exactly one argument: the path of the JSON config file
     * @return process exit code: {@link #EXIT_OK}, {@link #EXIT_USAGE}, {@link #EXIT_CONFIG} or
     *         {@link #EXIT_FAILURE}
     */
    public final int run(String[] args) {
        if (args == null || args.length != 1) {
            System.err.println(usage());
            return EXIT_USAGE;
        }
        C loadedConfig;
        try {
            loadedConfig = loadConfig(Path.of(args[0]));
        } catch (ConfigException e) {
            System.err.println(e.getMessage());
            return EXIT_CONFIG;
        }
        return run(loadedConfig);
    }

    /**
     * Starts the service with an already loaded config, registers a shutdown hook and blocks
     * until the service stops. For entry points that parse their own command line.
     *
     * @return {@link #EXIT_OK} or {@link #EXIT_FAILURE}
     */
    public final int run(C config) {
        try {
            start(config);
        } catch (ServiceStartException e) {
            System.err.println(e.getMessage());
            return EXIT_FAILURE;
        }
        registerShutdownHook();
        try {
            awaitTermination();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
        }
        return state == State.FAILED ? EXIT_FAILURE : EXIT_OK;
    }

    /**
     * Initializes and starts the service with an already loaded config.
     *
     * @throws IllegalStateException if the service was started before
     * @throws ServiceStartException if a lifecycle hook fails; components started so far are
     *                               stopped again
     */
    public final void start(C config) {
        Objects.requireNonNull(config, "config");
        synchronized (lifecycleLock) {
            if (state != State.NEW) {
                throw new IllegalStateException("Service can only be started once, current state: " + state);
            }
            this.config = config;
            state = State.STARTING;
            log.info("Starting {}", config.serviceId());
            try {
                initialize(config);
                startComponents();
            } catch (Exception e) {
                log.error("Starting {} failed", config.serviceId(), e);
                stopComponentsSafely();
                state = State.FAILED;
                terminated.countDown();
                throw new ServiceStartException("Starting " + config.serviceId() + " failed: " + e.getMessage(), e);
            }
            state = State.RUNNING;
            log.info("{} is running", config.serviceId());
        }
    }

    /** Stops the service. Calling it more than once, or before {@link #start}, is harmless. */
    public final void stop() {
        synchronized (lifecycleLock) {
            if (state == State.NEW) {
                state = State.STOPPED;
                terminated.countDown();
                return;
            }
            if (state != State.RUNNING) {
                return;
            }
            state = State.STOPPING;
            log.info("Stopping {}", config.serviceId());
            stopComponentsSafely();
            state = State.STOPPED;
            log.info("{} stopped", config.serviceId());
            removeShutdownHook();
            terminated.countDown();
        }
    }

    /** Blocks until the service has stopped or failed. */
    public final void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    /** Blocks until the service has stopped or failed, at most {@code timeout}. */
    public final boolean awaitTermination(Duration timeout) throws InterruptedException {
        return terminated.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public final State state() {
        return state;
    }

    /** The config the service was started with, or {@code null} before {@link #start}. */
    protected final C config() {
        return config;
    }

    /** Loads and validates the config file. Override only to change where configs come from. */
    protected C loadConfig(Path file) {
        return configLoader.load(file, configType);
    }

    /** Usage text printed when the arguments are wrong. */
    protected String usage() {
        return "Usage: java -jar <service>.jar <config.json>";
    }

    /** Creates the service's components from the config. Nothing should run yet. */
    protected abstract void initialize(C config) throws Exception;

    /** Starts threads, sockets and timers created in {@link #initialize}. */
    protected abstract void startComponents() throws Exception;

    /**
     * Stops everything started so far and releases resources. Also called after a failed start,
     * so it must cope with components that were never created or started.
     */
    protected abstract void stopComponents();

    private void stopComponentsSafely() {
        try {
            stopComponents();
        } catch (RuntimeException e) {
            log.error("Error while stopping components", e);
        }
    }

    private void registerShutdownHook() {
        synchronized (lifecycleLock) {
            if (state != State.RUNNING) {
                return;
            }
            shutdownHook = new Thread(this::stop, "shutdown-hook");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    private void removeShutdownHook() {
        if (shutdownHook == null || Thread.currentThread() == shutdownHook) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // The JVM is already shutting down; the hook is running or has run.
        }
        shutdownHook = null;
    }
}
