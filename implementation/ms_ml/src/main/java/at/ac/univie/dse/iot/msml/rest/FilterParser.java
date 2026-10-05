package at.ac.univie.dse.iot.msml.rest;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.msml.storage.PageRequest;
import at.ac.univie.dse.iot.msml.storage.ResultFilter;
import at.ac.univie.dse.iot.msml.storage.ResultKind;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns query parameters into a {@link ResultFilter} and a {@link PageRequest}. Unknown parameters
 * and invalid values are rejected with 400, naming the parameter, so typos never silently widen a
 * filter (which matters most for bulk deletes).
 *
 * <p>Filters: {@code dataType} (repeatable), {@code chainId} (anomalies only), {@code from},
 * {@code to} (ISO-8601 instants), {@code commented} (true/false). Paging: {@code limit}
 * (1–500, default 50), {@code offset} (default 0), {@code sort} ({@code occurredAt,desc} default,
 * or {@code occurredAt,asc}).
 */
final class FilterParser {

    private static final Set<String> FILTERS = Set.of("dataType", "from", "to", "commented");
    private static final Set<String> PAGING = Set.of("limit", "offset", "sort");
    private static final String CHAIN = "chainId";

    private FilterParser() {
    }

    /** Filter and page of a list request. */
    static ResultFilter filterForList(ResultKind kind, Map<String, List<String>> params) {
        rejectUnknown(kind, params, true);
        return filter(kind, params);
    }

    /** Filter of a bulk delete: no paging parameters. */
    static ResultFilter filterForDelete(ResultKind kind, Map<String, List<String>> params) {
        rejectUnknown(kind, params, false);
        return filter(kind, params);
    }

    static PageRequest page(Map<String, List<String>> params) {
        int limit = integer(params, "limit", PageRequest.DEFAULT_LIMIT);
        if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between 1 and " + PageRequest.MAX_LIMIT);
        }
        int offset = integer(params, "offset", 0);
        if (offset < 0) {
            throw ApiException.badRequest("offset must be >= 0");
        }
        String sort = single(params, "sort");
        PageRequest.SortOrder order;
        if (sort == null || sort.equals("occurredAt,desc")) {
            order = PageRequest.SortOrder.NEWEST_FIRST;
        } else if (sort.equals("occurredAt,asc")) {
            order = PageRequest.SortOrder.OLDEST_FIRST;
        } else {
            throw ApiException.badRequest("sort must be occurredAt,desc or occurredAt,asc");
        }
        return new PageRequest(limit, offset, order);
    }

    private static ResultFilter filter(ResultKind kind, Map<String, List<String>> params) {
        Set<String> dataTypes = new LinkedHashSet<>();
        for (String value : params.getOrDefault("dataType", List.of())) {
            try {
                dataTypes.add(DataType.of(value).value());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("dataType: " + e.getMessage());
            }
        }
        Instant from = instant(params, "from");
        Instant to = instant(params, "to");
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("from must not be after to");
        }
        return new ResultFilter(kind, dataTypes, single(params, CHAIN), from, to, bool(params, "commented"), null, null);
    }

    private static void rejectUnknown(ResultKind kind, Map<String, List<String>> params, boolean paging) {
        for (String name : params.keySet()) {
            boolean known = FILTERS.contains(name)
                    || (paging && PAGING.contains(name))
                    || (kind == ResultKind.ANOMALY && name.equals(CHAIN));
            if (!known) {
                throw ApiException.badRequest("unknown parameter " + name + (name.equals(CHAIN)
                        ? " (only anomalies have a chain)" : ""));
            }
        }
    }

    private static String single(Map<String, List<String>> params, String name) {
        List<String> values = params.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw ApiException.badRequest(name + " may only be given once");
        }
        String value = values.get(0).strip();
        if (value.isEmpty()) {
            throw ApiException.badRequest(name + " must not be empty");
        }
        return value;
    }

    private static int integer(Map<String, List<String>> params, String name, int fallback) {
        String value = single(params, name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " must be a number");
        }
    }

    private static Instant instant(Map<String, List<String>> params, String name) {
        String value = single(params, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(name + " must be an ISO-8601 instant such as 2021-11-20T14:00:00Z");
        }
    }

    private static Boolean bool(Map<String, List<String>> params, String name) {
        String value = single(params, name);
        if (value == null) {
            return null;
        }
        if (value.equals("true") || value.equals("false")) {
            return Boolean.valueOf(value);
        }
        throw ApiException.badRequest(name + " must be true or false");
    }
}
