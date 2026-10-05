package at.ac.univie.dse.iot.msml.storage;

/**
 * A page of a result list, ordered by {@code occurredAt} (ties by id).
 *
 * @param limit  1..{@value #MAX_LIMIT}
 * @param offset results to skip, &gt;= 0
 * @param order  newest or oldest first
 */
public record PageRequest(int limit, int offset, SortOrder order) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    /** Sort direction by {@code occurredAt}. */
    public enum SortOrder {
        NEWEST_FIRST,
        OLDEST_FIRST
    }

    public PageRequest {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + " but was " + limit);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0 but was " + offset);
        }
        if (order == null) {
            throw new IllegalArgumentException("order must be set");
        }
    }

    /** The first {@value #DEFAULT_LIMIT} results, newest first. */
    public static PageRequest firstPage() {
        return new PageRequest(DEFAULT_LIMIT, 0, SortOrder.NEWEST_FIRST);
    }
}
