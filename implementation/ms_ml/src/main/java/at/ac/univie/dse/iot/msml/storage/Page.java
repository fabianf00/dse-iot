package at.ac.univie.dse.iot.msml.storage;

import java.util.List;

/**
 * One page of results.
 *
 * @param total all results matching the filter, not only those on this page
 */
public record Page<T>(List<T> items, long total, int limit, int offset) {

    public Page {
        items = List.copyOf(items);
    }
}
