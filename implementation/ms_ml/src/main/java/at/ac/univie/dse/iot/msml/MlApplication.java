package at.ac.univie.dse.iot.msml;

import at.ac.univie.dse.iot.common.service.AbstractMicroService;

/**
 * Entry point of MS ML.
 *
 * <p>Usage: {@code java -jar ms_ml.jar <config.json>}
 */
public final class MlApplication {

    private MlApplication() {
    }

    public static void main(String[] args) {
        int exitCode = new MlService().run(args);
        if (exitCode != AbstractMicroService.EXIT_OK) {
            System.exit(exitCode);
        }
    }
}
