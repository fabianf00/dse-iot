package at.ac.univie.dse.iot.clntml;

import java.nio.file.Path;

/**
 * Command line of CLNT ML.
 *
 * @param configFile     path of the JSON config
 * @param pollIntervalMs {@code --interval <ms>}, or {@code null} to use the config value
 * @param once           {@code --once}: poll every endpoint once, print, and exit
 * @param status         {@code --status}: print the status of every endpoint and exit
 * @param cursorFile     {@code --cursor-file <path>}: where to keep the cursors between runs, or {@code null}
 */
public record ClientArguments(Path configFile, Long pollIntervalMs, boolean once, boolean status, Path cursorFile) {

    public static final String USAGE = """
            Usage: java -jar clnt_ml.jar <config.json> [--interval <ms>] [--once | --status] [--cursor-file <path>]
              --interval <ms>        poll interval, overrides pollIntervalMs of the config
              --once                 poll every ML instance once, print new events and exit
                                     (exit code 0 if all were reachable, 1 otherwise)
              --status               print the status of every ML instance and exit
              --cursor-file <path>   remember what was printed, so the next run continues there""";

    /**
     * @throws IllegalArgumentException with a message for the user if the arguments are invalid
     */
    public static ClientArguments parse(String[] args) {
        Path configFile = null;
        Long interval = null;
        boolean once = false;
        boolean status = false;
        Path cursorFile = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--once" -> once = true;
                case "--status" -> status = true;
                case "--interval" -> interval = parseInterval(valueOf(args, ++i, arg));
                case "--cursor-file" -> cursorFile = Path.of(valueOf(args, ++i, arg));
                default -> {
                    if (arg.startsWith("--")) {
                        throw new IllegalArgumentException("unknown option " + arg);
                    }
                    if (configFile != null) {
                        throw new IllegalArgumentException("only one config file may be given");
                    }
                    configFile = Path.of(arg);
                }
            }
        }
        if (configFile == null) {
            throw new IllegalArgumentException("missing config file");
        }
        if (once && status) {
            throw new IllegalArgumentException("--once and --status cannot be combined");
        }
        return new ClientArguments(configFile, interval, once, status, cursorFile);
    }

    private static String valueOf(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static long parseInterval(String value) {
        try {
            long interval = Long.parseLong(value);
            if (interval <= 0) {
                throw new IllegalArgumentException("--interval must be > 0");
            }
            return interval;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--interval must be a number of milliseconds, not '" + value + "'");
        }
    }
}
