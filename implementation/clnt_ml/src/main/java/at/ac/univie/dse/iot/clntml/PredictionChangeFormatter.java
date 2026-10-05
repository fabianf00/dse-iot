package at.ac.univie.dse.iot.clntml;

import at.ac.univie.dse.iot.mlapi.PredictionChangeDto;
import at.ac.univie.dse.iot.mlapi.PredictionDto;
import java.util.Locale;

/** {@code co2  680–720 ppm (was 640–680 ppm), confidence 34 %, n=480}. */
public final class PredictionChangeFormatter implements EventFormatter<PredictionChangeDto> {

    @Override
    public String kind() {
        return "PREDICTION";
    }

    @Override
    public String format(PredictionChangeDto change) {
        PredictionDto current = change.current();
        String previous = change.previous() == null ? "first prediction" : "was " + range(change.previous());
        return String.format(Locale.ROOT, "%-18s %s (%s), confidence %.0f %%, n=%d", change.dataType(), range(current),
                previous, current.confidence() * 100, current.sampleCount());
    }

    private static String range(PredictionDto prediction) {
        String lower = EventFormatter.value(prediction.binLower(), null);
        return lower + "–" + EventFormatter.value(prediction.binUpper(), prediction.unit());
    }
}
