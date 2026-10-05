package at.ac.univie.dse.iot.msml.storage;

import java.time.Instant;
import java.util.Set;

/**
 * Which stored results to list, count or delete. Every {@code null} (or empty) criterion matches
 * everything; criteria are combined with AND.
 *
 * @param kind           anomalies or predictions; {@code null} for both
 * @param dataTypes      one of these data types
 * @param chainId        behaviour chain (anomalies only)
 * @param from           {@code occurredAt >= from}
 * @param to             {@code occurredAt <= to}
 * @param commented      {@code true}: has comments, {@code false}: has none
 * @param mlInstance     produced by this ML instance
 * @param recordedBefore {@code recordedAt <= recordedBefore}; bulk deletes use it, so results recorded
 *                       after the delete started are not affected
 */
public record ResultFilter(ResultKind kind, Set<String> dataTypes, String chainId, Instant from, Instant to,
        Boolean commented, String mlInstance, Instant recordedBefore) {

    public ResultFilter {
        dataTypes = dataTypes == null ? Set.of() : Set.copyOf(dataTypes);
    }

    /** Matches all results of {@code kind} ({@code null} for both kinds). */
    public static ResultFilter all(ResultKind kind) {
        return new ResultFilter(kind, Set.of(), null, null, null, null, null, null);
    }

    public ResultFilter withRecordedBefore(Instant instant) {
        return new ResultFilter(kind, dataTypes, chainId, from, to, commented, mlInstance, instant);
    }

    /** Whether any criterion besides the kind is set (guards bulk deletes of everything). */
    public boolean hasCriteria() {
        return !dataTypes.isEmpty() || chainId != null || from != null || to != null || commented != null
                || mlInstance != null;
    }
}
