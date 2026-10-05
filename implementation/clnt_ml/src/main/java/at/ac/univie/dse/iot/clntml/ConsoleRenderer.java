package at.ac.univie.dse.iot.clntml;

import at.ac.univie.dse.iot.mlapi.AnomalyDto;
import at.ac.univie.dse.iot.mlapi.MlEventDto;
import at.ac.univie.dse.iot.mlapi.PredictionChangeDto;
import at.ac.univie.dse.iot.mlapi.ServiceStatusDto;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TreeMap;

/**
 * Prints one line per event or status message:
 *
 * <pre>
 * [14:03:05] ml-1  ANOMALY     temp-sudden-drop   expected temperature &gt; 20.0 °C, observed 14.2 °C
 * [14:03:05] ml-1  PREDICTION  co2                680–720 ppm (was 640–680 ppm), confidence 34 %, n=480
 * [14:03:10] ml-1  -- no new anomalies or prediction changes --
 * [14:03:15] ml-1  WARN        unreachable (Connection refused), retry in 2 s
 * </pre>
 *
 * Event lines show when the event happened, status lines the current time. Lines from several
 * endpoints are never interleaved.
 */
public final class ConsoleRenderer {

    private static final String LINE = "[%s] %-5s %-11s %s";

    private final PrintStream out;
    private final DateTimeFormatter time;
    private final Clock clock;
    private final AnomalyFormatter anomalies = new AnomalyFormatter();
    private final PredictionChangeFormatter predictions = new PredictionChangeFormatter();

    public ConsoleRenderer(PrintStream out, ZoneId zone, Clock clock) {
        this.out = out;
        this.time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone);
        this.clock = clock;
    }

    public synchronized void events(String endpoint, List<MlEventDto> events) {
        for (MlEventDto event : events) {
            if (event instanceof AnomalyDto anomaly) {
                line(event.occurredAt(), endpoint, anomalies.kind(), anomalies.format(anomaly));
            } else if (event instanceof PredictionChangeDto change) {
                line(event.occurredAt(), endpoint, predictions.kind(), predictions.format(change));
            }
        }
        out.flush();
    }

    public synchronized void nothingNew(String endpoint) {
        status(endpoint, "-- no new anomalies or prediction changes --");
    }

    public synchronized void info(String endpoint, String message) {
        line(clock.instant(), endpoint, "INFO", message);
        out.flush();
    }

    /** Prints the status of an ML instance on several lines. */
    public synchronized void status(String endpoint, String url, ServiceStatusDto status) {
        line(clock.instant(), endpoint, "STATUS", status.serviceId() + " at " + url + ", running since "
                + time.format(status.startedAt()) + " (epoch " + status.serverEpoch() + ")");
        out.println("    data types:  " + (status.dataTypes().isEmpty() ? "none yet" : String.join(", ", status.dataTypes())));
        new TreeMap<>(status.fogStatus()).forEach((fog, state) -> out.println("    " + fog + ": " + state));
        out.println("    jobs:        " + status.queueSize() + " queued, " + status.droppedJobs() + " dropped");
        out.println("    events:      " + status.lastEventSequence());
        out.flush();
    }

    public synchronized void warn(String endpoint, String message) {
        line(clock.instant(), endpoint, "WARN", message);
        out.flush();
    }

    private void status(String endpoint, String message) {
        out.println(String.format("[%s] %-5s %s", time.format(clock.instant()), endpoint, message));
        out.flush();
    }

    private void line(Instant at, String endpoint, String kind, String text) {
        out.println(String.format(LINE, time.format(at), endpoint, kind, text));
    }
}
