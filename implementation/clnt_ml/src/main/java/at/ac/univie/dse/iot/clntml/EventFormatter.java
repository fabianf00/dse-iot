package at.ac.univie.dse.iot.clntml;

import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.mlapi.MlEventDto;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns one event into the text of one console line (Strategy, one per event type).
 *
 * @param <T> the event type
 */
public interface EventFormatter<T extends MlEventDto> {

    /** Label of the event kind, e.g. {@code ANOMALY}. */
    String kind();

    /** The line text after the kind. */
    String format(T event);

    /** A value with at most 2 decimals and the unit symbol, e.g. {@code 14.2 °C}. */
    static String value(double value, String unit) {
        String number = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        String symbol = symbol(unit);
        return symbol.isEmpty() ? number : number + " " + symbol;
    }

    /** Unit symbol for a unit name of the API, e.g. {@code CELSIUS} → {@code °C}; unknown names unchanged. */
    static String symbol(String unit) {
        if (unit == null) {
            return "";
        }
        try {
            return Unit.valueOf(unit).symbol();
        } catch (IllegalArgumentException e) {
            return unit;
        }
    }
}
