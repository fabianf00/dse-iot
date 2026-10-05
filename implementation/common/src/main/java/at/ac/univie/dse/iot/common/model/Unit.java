package at.ac.univie.dse.iot.common.model;

/** Physical unit of a sensor value. Serialized by its constant name, e.g. {@code "CELSIUS"}. */
public enum Unit {
    CELSIUS("°C"),
    PPM("ppm"),
    PERCENT("%"),
    HECTOPASCAL("hPa");

    private final String symbol;

    Unit(String symbol) {
        this.symbol = symbol;
    }

    /** Symbol for display, e.g. {@code °C}. */
    public String symbol() {
        return symbol;
    }
}
