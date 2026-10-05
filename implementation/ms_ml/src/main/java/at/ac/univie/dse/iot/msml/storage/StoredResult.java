package at.ac.univie.dse.iot.msml.storage;

import java.time.Instant;
import java.util.List;

/**
 * A stored result as the rest of MS ML sees it: immutable, detached from the database, with its
 * comments already loaded. Entities never leave the storage package.
 */
public sealed interface StoredResult permits StoredResult.StoredAnomaly, StoredResult.StoredPrediction {

    long id();

    ResultKind kind();

    String mlInstance();

    long serverEpoch();

    long eventSequence();

    String dataType();

    Instant occurredAt();

    Instant recordedAt();

    List<StoredComment> comments();

    /** A stored anomaly. */
    record StoredAnomaly(long id, String mlInstance, long serverEpoch, long eventSequence, String dataType,
            Instant occurredAt, Instant recordedAt, List<StoredComment> comments, String chainId,
            String chainDescription, String expected, double observedValue, String unit, Instant observedAt,
            Instant expectedUntil, StoredPhase past, StoredPhase current) implements StoredResult {

        public StoredAnomaly {
            comments = List.copyOf(comments);
        }

        @Override
        public ResultKind kind() {
            return ResultKind.ANOMALY;
        }
    }

    /** A stored prediction change; {@code previousBinLower/Upper} are null for the first prediction. */
    record StoredPrediction(long id, String mlInstance, long serverEpoch, long eventSequence, String dataType,
            Instant occurredAt, Instant recordedAt, List<StoredComment> comments, String unit, double binLower,
            double binUpper, double mostLikelyValue, double confidence, int sampleCount, Double previousBinLower,
            Double previousBinUpper) implements StoredResult {

        public StoredPrediction {
            comments = List.copyOf(comments);
        }

        @Override
        public ResultKind kind() {
            return ResultKind.PREDICTION;
        }
    }

    /** A phase of a behaviour chain that held. */
    record StoredPhase(String condition, Instant from, Instant to, int samples) {
    }

    /** A comment on a stored result. {@code updatedAt} is null until the comment is edited. */
    record StoredComment(long id, long resultId, String text, String author, Instant createdAt, Instant updatedAt) {
    }
}
