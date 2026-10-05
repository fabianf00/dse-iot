package at.ac.univie.dse.iot.msml.rest;

/** Parses ids from paths. */
final class Ids {

    private Ids() {
    }

    /** A positive number, or 400. */
    static long parse(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw ApiException.badRequest("id must be a positive number, not '" + value + "'");
    }
}
