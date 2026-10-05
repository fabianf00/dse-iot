package at.ac.univie.dse.iot.msml.storage;

import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredComment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Historic ML results and their comments (Repository). Returns immutable {@link StoredResult}s,
 * never entities.
 */
public interface ResultRepository {

    /**
     * Stores a newly recorded result.
     *
     * @return {@code false} if the event behind it is already stored (recording is idempotent)
     */
    boolean record(MlResultEntity result);

    Optional<StoredResult> find(ResultKind kind, long id);

    Page<StoredResult> search(ResultFilter filter, PageRequest page);

    long count(ResultFilter filter);

    /** Deletes a result and its comments; {@code false} if there is no such result. */
    boolean delete(ResultKind kind, long id);

    /** Deletes every result matching {@code filter}, with their comments; returns how many. */
    long deleteMatching(ResultFilter filter);

    /** Comments of a result, oldest first; empty if there is no such result. */
    Optional<List<StoredComment>> comments(ResultKind kind, long resultId);

    /** Adds a comment; empty if there is no such result. */
    Optional<StoredComment> addComment(ResultKind kind, long resultId, String text, String author, Instant now);

    /** Replaces a comment's text; empty if there is no such comment. */
    Optional<StoredComment> updateComment(long commentId, String text, Instant now);

    /** Deletes a comment; {@code false} if there is no such comment. */
    boolean deleteComment(long commentId);
}
