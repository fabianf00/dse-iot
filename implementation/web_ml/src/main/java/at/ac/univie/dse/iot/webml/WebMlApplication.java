package at.ac.univie.dse.iot.webml;

import at.ac.univie.dse.iot.common.service.AbstractMicroService;

/**
 * Entry point of WEB ML.
 *
 * <p>Usage: {@code java -jar web_ml.jar <config.json>}, then open {@code http://127.0.0.1:50400/}.
 */
public final class WebMlApplication {

    private WebMlApplication() {
    }

    public static void main(String[] args) {
        int exitCode = new WebMlService().run(args);
        if (exitCode != AbstractMicroService.EXIT_OK) {
            System.exit(exitCode);
        }
    }
}
