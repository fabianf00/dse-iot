package at.ac.univie.dse.iot.msml.results;

import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.anomaly;
import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.msml.storage.AnomalyEntity;
import at.ac.univie.dse.iot.msml.storage.Database;
import at.ac.univie.dse.iot.msml.storage.JpaResultRepository;
import at.ac.univie.dse.iot.msml.storage.PageRequest;
import at.ac.univie.dse.iot.msml.storage.ResultFilter;
import at.ac.univie.dse.iot.msml.storage.ResultKind;
import at.ac.univie.dse.iot.msml.storage.StorageFixtures;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredComment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResultServiceTest {

    private static final Instant NOW = at(1_000);

    private final Database database = StorageFixtures.memoryDatabase();
    private final JpaResultRepository repository = new JpaResultRepository(database);
    private final ResultService service = new ResultService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void close() {
        database.close();
    }

    private long storeAnomaly(long sequence, Instant recordedAt) {
        repository.record(new AnomalyEntity("ml-1", 1, anomaly(sequence, "temperature", "chain-a", 10, 14), recordedAt));
        return service.search(ResultFilter.all(ResultKind.ANOMALY), PageRequest.firstPage()).items().stream()
                .filter(r -> r.eventSequence() == sequence).findFirst().orElseThrow().id();
    }

    @Test
    void commentsAreTrimmedAndStampedWithTheCurrentTime() {
        long id = storeAnomaly(1, at(10));

        StoredComment comment = service.addComment(ResultKind.ANOMALY, id, "  window open \n", "  dispatcher ").orElseThrow();

        assertEquals("window open", comment.text());
        assertEquals("dispatcher", comment.author());
        assertEquals(NOW, comment.createdAt());
        assertNull(service.addComment(ResultKind.ANOMALY, id, "x", "   ").orElseThrow().author(), "blank author → none");
    }

    @Test
    void invalidCommentsAreRejected() {
        long id = storeAnomaly(1, at(10));

        assertEquals("text must not be empty", assertThrows(InvalidRequestException.class,
                () -> service.addComment(ResultKind.ANOMALY, id, " ", null)).getMessage());
        assertEquals("text must not be empty", assertThrows(InvalidRequestException.class,
                () -> service.addComment(ResultKind.ANOMALY, id, null, null)).getMessage());
        assertEquals("text must have at most 2000 characters", assertThrows(InvalidRequestException.class,
                () -> service.addComment(ResultKind.ANOMALY, id, "x".repeat(2001), null)).getMessage());
        assertEquals("author must have at most 100 characters", assertThrows(InvalidRequestException.class,
                () -> service.addComment(ResultKind.ANOMALY, id, "ok", "a".repeat(101))).getMessage());
        assertEquals("text must not be empty", assertThrows(InvalidRequestException.class,
                () -> service.updateComment(1, "")).getMessage());
    }

    @Test
    void bulkDeleteNeedsAFilter() {
        storeAnomaly(1, at(10));

        assertEquals("at least one filter is required to delete several results", assertThrows(
                InvalidRequestException.class, () -> service.deleteMatching(ResultFilter.all(ResultKind.ANOMALY)))
                .getMessage());
        assertEquals(1, service.count(ResultFilter.all(null)));
    }

    @Test
    void bulkDeleteSparesResultsRecordedAfterTheRequest() {
        storeAnomaly(1, NOW.minusSeconds(1));
        storeAnomaly(2, NOW.plusSeconds(1));

        long deleted = service.deleteMatching(new ResultFilter(ResultKind.ANOMALY, Set.of("temperature"), null, null, null,
                null, null, null));

        assertEquals(1, deleted);
        assertTrue(service.search(ResultFilter.all(null), PageRequest.firstPage()).items().stream()
                .allMatch(r -> r.eventSequence() == 2));
    }
}
