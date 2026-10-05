package at.ac.univie.dse.iot.msiot;

import at.ac.univie.dse.iot.common.service.AbstractMicroService;

/**
 * Entry point of MS IoT.
 *
 * <p>Usage: {@code java -jar ms_iot.jar <config.json>}
 */
public final class IotDeviceApplication {

    private IotDeviceApplication() {
    }

    public static void main(String[] args) {
        int exitCode = new IotDeviceService().run(args);
        if (exitCode != AbstractMicroService.EXIT_OK) {
            System.exit(exitCode);
        }
    }
}
