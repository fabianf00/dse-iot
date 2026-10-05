package at.ac.univie.dse.iot.clntml;

import at.ac.univie.dse.iot.mlapi.AnomalyDto;

/** {@code temp-sudden-drop  expected temperature > 20.0 °C, observed 14.2 °C}. */
public final class AnomalyFormatter implements EventFormatter<AnomalyDto> {

    @Override
    public String kind() {
        return "ANOMALY";
    }

    @Override
    public String format(AnomalyDto anomaly) {
        String unit = EventFormatter.symbol(anomaly.unit());
        String expected = unit.isEmpty() ? anomaly.expected() : anomaly.expected() + " " + unit;
        return String.format("%-18s expected %s, observed %s", anomaly.chainId(), expected,
                EventFormatter.value(anomaly.observedValue(), anomaly.unit()));
    }
}
