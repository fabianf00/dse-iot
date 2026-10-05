package at.ac.univie.dse.iot.system;

import at.ac.univie.dse.iot.common.service.AbstractMicroService;
import at.ac.univie.dse.iot.msfog.FogNodeService;
import at.ac.univie.dse.iot.msiot.IotDeviceService;
import at.ac.univie.dse.iot.msml.MlService;
import at.ac.univie.dse.iot.system.TestConfigs.Layout;
import at.ac.univie.dse.iot.webml.WebMlService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole minimum layout in this JVM: ML, 2 fogs, 6 IoT devices and WEB ML, started in the
 * same order as {@code scripts/start-all.sh} and stopped in reverse order.
 */
final class RunningSystem implements AutoCloseable {

    private final Layout layout;
    private final List<AbstractMicroService<?>> services = new ArrayList<>();

    private RunningSystem(Layout layout) {
        this.layout = layout;
    }

    static RunningSystem start(Path dataDir) {
        RunningSystem system = new RunningSystem(Layout.pick());
        try {
            system.startAll(dataDir);
        } catch (RuntimeException e) {
            system.close();
            throw e;
        }
        return system;
    }

    private void startAll(Path dataDir) {
        MlService ml = new MlService();
        ml.start(TestConfigs.ml(layout, dataDir));
        services.add(ml);
        for (var dataType : List.of(TestConfigs.TEMPERATURE, TestConfigs.CO2)) {
            FogNodeService fog = new FogNodeService();
            fog.start(TestConfigs.fog(layout, dataType));
            services.add(fog);
        }
        List<String> devices = new ArrayList<>(TestConfigs.TEMPERATURE_DEVICES);
        devices.addAll(TestConfigs.CO2_DEVICES);
        for (int i = 0; i < devices.size(); i++) {
            IotDeviceService device = new IotDeviceService();
            device.start(TestConfigs.device(layout, devices.get(i), i));
            services.add(device);
        }
        WebMlService web = new WebMlService();
        web.start(TestConfigs.web(layout));
        services.add(web);
    }

    Layout layout() {
        return layout;
    }

    /** All services that are not RUNNING (empty while the system is healthy). */
    List<String> notRunning() {
        return services.stream()
                .filter(service -> service.state() != AbstractMicroService.State.RUNNING)
                .map(service -> service.getClass().getSimpleName() + " " + service.state())
                .toList();
    }

    @Override
    public void close() {
        for (int i = services.size() - 1; i >= 0; i--) {
            services.get(i).stop();
        }
        services.clear();
    }
}
