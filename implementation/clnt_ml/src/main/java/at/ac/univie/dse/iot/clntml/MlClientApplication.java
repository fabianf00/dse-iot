package at.ac.univie.dse.iot.clntml;

import at.ac.univie.dse.iot.common.config.ConfigException;
import at.ac.univie.dse.iot.common.config.ConfigLoader;
import at.ac.univie.dse.iot.common.service.AbstractMicroService;
import java.io.PrintStream;

/**
 * Entry point of CLNT ML.
 *
 * <p>Usage: {@code java -jar clnt_ml.jar <config.json> [--interval <ms>] [--once | --status] [--cursor-file <path>]}
 */
public final class MlClientApplication {

    /** Response timeout of RMI calls; a hanging server must not block the client forever. */
    private static final long RESPONSE_TIMEOUT_MS = 10_000;

    private MlClientApplication() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != AbstractMicroService.EXIT_OK) {
            System.exit(exitCode);
        }
    }

    /** Runs the client; returns the exit code. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        ClientArguments arguments;
        try {
            arguments = ClientArguments.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(ClientArguments.USAGE);
            return AbstractMicroService.EXIT_USAGE;
        }
        ClientConfig config;
        try {
            config = new ConfigLoader().load(arguments.configFile(), ClientConfig.class);
            if (arguments.pollIntervalMs() != null) {
                config = config.withPollInterval(arguments.pollIntervalMs());
            }
        } catch (ConfigException e) {
            err.println(e.getMessage());
            return AbstractMicroService.EXIT_CONFIG;
        }
        TimeoutRmiSocketFactory.install(config.connectTimeoutMs(), RESPONSE_TIMEOUT_MS);
        MlClient client;
        try {
            client = new MlClient(out, arguments.cursorFile());
            if (arguments.status()) {
                return client.runStatus(config);
            }
            return arguments.once() ? client.runOnce(config) : client.run(config);
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return AbstractMicroService.EXIT_FAILURE;
        }
    }
}
