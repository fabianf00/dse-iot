package at.ac.univie.dse.iot.msfog;

import at.ac.univie.dse.iot.common.service.AbstractMicroService;

/**
 * Entry point of MS Fog.
 *
 * <p>Usage: {@code java -jar ms_fog.jar <config.json>}
 */
public final class FogNodeApplication {

    private FogNodeApplication() {
    }

    public static void main(String[] args) {
        int exitCode = new FogNodeService().run(args);
        if (exitCode != AbstractMicroService.EXIT_OK) {
            System.exit(exitCode);
        }
    }
}
