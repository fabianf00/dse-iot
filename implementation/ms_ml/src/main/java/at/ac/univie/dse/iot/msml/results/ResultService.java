package at.ac.univie.dse.iot.msml.results;

import at.ac.univie.dse.iot.msml.storage.CommentEntity;
import at.ac.univie.dse.iot.msml.storage.Page;
import at.ac.univie.dse.iot.msml.storage.PageRequest;
import at.ac.univie.dse.iot.msml.storage.ResultFilter;
import at.ac.univie.dse.iot.msml.storage.ResultKind;
import at.ac.univie.dse.iot.msml.storage.ResultRepository;
import at.ac.univie.dse.iot.msml.storage.StoredResult;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredComment;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Use cases for managing stored ML results: load, filter, delete and comment. Validates input
 * (invalid input → {@link InvalidRequestException}), so the REST layer only translates between
 * HTTP and these calls.
 */
public final class ResultService {

    private final ResultRepository repository;
    private final Clock clock;

    public ResultService(ResultRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Page<StoredResult> search(ResultFilter filter, PageRequest page) {
        return repository.search(filter, page);
    }

    public long count(ResultFilter filter) {
        return repository.count(filter);
    }

    public Optional<StoredResult> find(ResultKind kind, long id) {
        return repository.find(kind, id);
    }

    public boolean delete(ResultKind kind, long id) {
        return repository.delete(kind, id);
    }

    /**
     * Deletes all results that match {@code filter} and were stored before this call. Results the
     * recorder writes while the delete runs are not affected, so a deleted period cannot fill up
     * again with late events.
     *
     * @throws InvalidRequestException if the filter has no criterion (would delete everything)
     */
    public long deleteMatching(ResultFilter filter) {
        if (!filter.hasCriteria()) {
            throw new InvalidRequestException("at least one filter is required to delete several results");
        }
        return repository.deleteMatching(filter.withRecordedBefore(clock.instant()));
    }

    public Optional<List<StoredComment>> comments(ResultKind kind, long resultId) {
        return repository.comments(kind, resultId);
    }

    /** Adds a comment; empty if there is no such result. */
    public Optional<StoredComment> addComment(ResultKind kind, long resultId, String text, String author) {
        return repository.addComment(kind, resultId, validText(text), validAuthor(author), clock.instant());
    }

    /** Replaces a comment's text; empty if there is no such comment. */
    public Optional<StoredComment> updateComment(long commentId, String text) {
        return repository.updateComment(commentId, validText(text), clock.instant());
    }

    public boolean deleteComment(long commentId) {
        return repository.deleteComment(commentId);
    }

    private static String validText(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidRequestException("text must not be empty");
        }
        String trimmed = text.strip();
        if (trimmed.length() > CommentEntity.MAX_TEXT_LENGTH) {
            throw new InvalidRequestException("text must have at most " + CommentEntity.MAX_TEXT_LENGTH + " characters");
        }
        return trimmed;
    }

    private static String validAuthor(String author) {
        if (author == null || author.isBlank()) {
            return null;
        }
        String trimmed = author.strip();
        if (trimmed.length() > CommentEntity.MAX_AUTHOR_LENGTH) {
            throw new InvalidRequestException("author must have at most " + CommentEntity.MAX_AUTHOR_LENGTH + " characters");
        }
        return trimmed;
    }
}
