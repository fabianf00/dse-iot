package at.ac.univie.dse.iot.msml.storage;

import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.EPOCH;
import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.anomaly;
import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.anomalyEntity;
import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.at;
import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.prediction;
import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.predictionEntity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.msml.events.MlEvent;
import at.ac.univie.dse.iot.msml.storage.PageRequest.SortOrder;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredAnomaly;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredComment;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredPrediction;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JpaResultRepositoryTest {

    private final Database database = StorageFixtures.memoryDatabase();
    private final JpaResultRepository repository = new JpaResultRepository(database);

    @AfterEach
    void close() {
        database.close();
    }

    private long idOf(ResultKind kind, long sequence) {
        return repository.search(ResultFilter.all(kind), new PageRequest(500, 0, SortOrder.OLDEST_FIRST)).items().stream()
                .filter(r -> r.eventSequence() == sequence)
                .findFirst().orElseThrow().id();
    }

    private static ResultFilter filter(ResultKind kind, Set<String> dataTypes, String chainId, Instant from,
            Instant to, Boolean commented) {
        return new ResultFilter(kind, dataTypes, chainId, from, to, commented, null, null);
    }

    private List<Long> sequences(ResultFilter filter) {
        return repository.search(filter, new PageRequest(500, 0, SortOrder.OLDEST_FIRST)).items().stream()
                .map(StoredResult::eventSequence).toList();
    }

    /** Anomalies 1 (temp, chain a, t=10), 2 (co2, chain b, t=20), 4 (temp, chain a, t=40); predictions 3 (temp, t=30), 5 (co2, t=50). */
    private void recordSample() {
        repository.record(anomalyEntity(anomaly(1, "temperature", "chain-a", 10, 14.2)));
        repository.record(anomalyEntity(anomaly(2, "co2", "chain-b", 20, 1400)));
        repository.record(predictionEntity(prediction(3, "temperature", 30, 20)));
        repository.record(anomalyEntity(anomaly(4, "temperature", "chain-a", 40, 15)));
        repository.record(predictionEntity(prediction(5, "co2", 50, 500)));
    }

    @Test
    void anomalyIsStoredWithAllFields() {
        MlEvent.AnomalyEvent event = anomaly(7, "temperature", "temp-sudden-drop", 100, 14.2);
        assertTrue(repository.record(anomalyEntity(event)));

        StoredAnomaly stored = assertInstanceOf(StoredAnomaly.class,
                repository.find(ResultKind.ANOMALY, idOf(ResultKind.ANOMALY, 7)).orElseThrow());

        assertEquals("ml-1", stored.mlInstance());
        assertEquals(EPOCH, stored.serverEpoch());
        assertEquals(7, stored.eventSequence());
        assertEquals("temperature", stored.dataType());
        assertEquals(at(100), stored.occurredAt());
        assertEquals(at(100).plusMillis(100), stored.recordedAt());
        assertEquals("temp-sudden-drop", stored.chainId());
        assertEquals("temp-sudden-drop description", stored.chainDescription());
        assertEquals("temperature > 20.0", stored.expected());
        assertEquals(14.2, stored.observedValue());
        assertEquals("CELSIUS", stored.unit());
        assertEquals(at(99), stored.observedAt());
        assertEquals(at(150), stored.expectedUntil());
        assertEquals(new StoredResult.StoredPhase("temperature > 20.0", at(-30), at(30), 6), stored.past());
        assertEquals(new StoredResult.StoredPhase("temperature > 20.0", at(30), at(90), 7), stored.current());
        assertTrue(stored.comments().isEmpty());
    }

    @Test
    void predictionIsStoredWithItsPreviousBin() {
        repository.record(predictionEntity(prediction(3, "co2", 30, 500)));

        StoredPrediction stored = assertInstanceOf(StoredPrediction.class,
                repository.find(ResultKind.PREDICTION, idOf(ResultKind.PREDICTION, 3)).orElseThrow());

        assertEquals("co2", stored.dataType());
        assertEquals("PPM", stored.unit());
        assertEquals(500, stored.binLower());
        assertEquals(550, stored.binUpper());
        assertEquals(525, stored.mostLikelyValue());
        assertEquals(0.5, stored.confidence());
        assertEquals(21, stored.sampleCount());
        assertEquals(450.0, stored.previousBinLower());
        assertEquals(500.0, stored.previousBinUpper());
    }

    @Test
    void recordingTheSameEventTwiceStoresItOnce() {
        MlEvent.AnomalyEvent event = anomaly(1, "temperature", "chain-a", 10, 14);

        assertTrue(repository.record(anomalyEntity(event)));
        assertFalse(repository.record(anomalyEntity(event)));
        assertEquals(1, repository.count(ResultFilter.all(null)));
        assertTrue(repository.record(new AnomalyEntity("ml-1", EPOCH + 1, event, at(10))), "same sequence, new epoch");
    }

    @Test
    void findChecksTheKind() {
        recordSample();
        long anomalyId = idOf(ResultKind.ANOMALY, 1);

        assertTrue(repository.find(ResultKind.PREDICTION, anomalyId).isEmpty());
        assertTrue(repository.find(ResultKind.ANOMALY, 999_999).isEmpty());
    }

    @Test
    void filtersSelectMatchingResults() {
        recordSample();

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), sequences(ResultFilter.all(null)));
        assertEquals(List.of(1L, 2L, 4L), sequences(ResultFilter.all(ResultKind.ANOMALY)));
        assertEquals(List.of(3L, 5L), sequences(ResultFilter.all(ResultKind.PREDICTION)));
        assertEquals(List.of(1L, 3L, 4L), sequences(filter(null, Set.of("temperature"), null, null, null, null)));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                sequences(filter(null, Set.of("temperature", "co2"), null, null, null, null)));
        assertEquals(List.of(1L, 4L), sequences(filter(ResultKind.ANOMALY, Set.of(), "chain-a", null, null, null)));
        assertEquals(List.of(2L, 3L, 4L), sequences(filter(null, Set.of(), null, at(20), at(40), null)));
        assertEquals(List.of(), sequences(new ResultFilter(null, Set.of(), null, null, null, null, "ml-2", null)));
        assertEquals(List.of(1L, 2L), sequences(new ResultFilter(null, Set.of(), null, null, null, null, null,
                at(20).plusMillis(100))));
    }

    @Test
    void commentedFilterFollowsComments() {
        recordSample();
        repository.addComment(ResultKind.ANOMALY, idOf(ResultKind.ANOMALY, 2), "checked", null, at(60));

        assertEquals(List.of(2L), sequences(filter(null, Set.of(), null, null, null, true)));
        assertEquals(List.of(1L, 3L, 4L, 5L), sequences(filter(null, Set.of(), null, null, null, false)));
    }

    @Test
    void pagesAreOrderedAndCounted() {
        recordSample();

        Page<StoredResult> newest = repository.search(ResultFilter.all(null), new PageRequest(2, 0, SortOrder.NEWEST_FIRST));
        Page<StoredResult> second = repository.search(ResultFilter.all(null), new PageRequest(2, 2, SortOrder.NEWEST_FIRST));
        Page<StoredResult> beyond = repository.search(ResultFilter.all(null), new PageRequest(2, 10, SortOrder.NEWEST_FIRST));

        assertEquals(List.of(5L, 4L), newest.items().stream().map(StoredResult::eventSequence).toList());
        assertEquals(5, newest.total());
        assertEquals(List.of(3L, 2L), second.items().stream().map(StoredResult::eventSequence).toList());
        assertTrue(beyond.items().isEmpty());
        assertEquals(5, beyond.total());
    }

    @Test
    void commentsCanBeAddedListedEditedAndDeleted() {
        recordSample();
        long id = idOf(ResultKind.ANOMALY, 1);

        StoredComment first = repository.addComment(ResultKind.ANOMALY, id, "window open", "dispatcher", at(60)).orElseThrow();
        repository.addComment(ResultKind.ANOMALY, id, "closed again", null, at(70));

        assertEquals(id, first.resultId());
        assertEquals(List.of("window open", "closed again"), repository.comments(ResultKind.ANOMALY, id).orElseThrow()
                .stream().map(StoredComment::text).toList());
        assertNull(first.updatedAt());

        StoredComment edited = repository.updateComment(first.id(), "window was open", at(80)).orElseThrow();
        assertEquals("window was open", edited.text());
        assertEquals(at(60), edited.createdAt());
        assertEquals(at(80), edited.updatedAt());
        assertEquals("dispatcher", edited.author());

        assertTrue(repository.deleteComment(first.id()));
        assertFalse(repository.deleteComment(first.id()));
        assertEquals(List.of("closed again"), repository.find(ResultKind.ANOMALY, id).orElseThrow().comments()
                .stream().map(StoredComment::text).toList());
    }

    @Test
    void commentsOnMissingResultsOrCommentsAreRejected() {
        recordSample();

        assertTrue(repository.addComment(ResultKind.ANOMALY, 999_999, "x", null, at(1)).isEmpty());
        assertTrue(repository.addComment(ResultKind.PREDICTION, idOf(ResultKind.ANOMALY, 1), "x", null, at(1)).isEmpty(),
                "the id belongs to an anomaly, not a prediction");
        assertTrue(repository.comments(ResultKind.ANOMALY, 999_999).isEmpty());
        assertTrue(repository.updateComment(999_999, "x", at(1)).isEmpty());
    }

    @Test
    void deletingAResultDeletesItsComments() {
        recordSample();
        long id = idOf(ResultKind.ANOMALY, 1);
        StoredComment comment = repository.addComment(ResultKind.ANOMALY, id, "gone soon", null, at(60)).orElseThrow();

        assertTrue(repository.delete(ResultKind.ANOMALY, id));

        assertTrue(repository.find(ResultKind.ANOMALY, id).isEmpty());
        assertTrue(repository.updateComment(comment.id(), "x", at(61)).isEmpty());
        assertFalse(repository.delete(ResultKind.ANOMALY, id));
        assertEquals(4, repository.count(ResultFilter.all(null)));
    }

    @Test
    void deleteMatchingRemovesOnlyMatchingResultsAndTheirComments() {
        recordSample();
        repository.addComment(ResultKind.ANOMALY, idOf(ResultKind.ANOMALY, 1), "on a deleted result", null, at(60));
        StoredComment kept = repository.addComment(ResultKind.ANOMALY, idOf(ResultKind.ANOMALY, 2), "kept", null, at(60))
                .orElseThrow();

        long deleted = repository.deleteMatching(filter(null, Set.of("temperature"), null, null, null, null));

        assertEquals(3, deleted);
        assertEquals(List.of(2L, 5L), sequences(ResultFilter.all(null)));
        assertEquals(List.of(kept), repository.comments(ResultKind.ANOMALY, idOf(ResultKind.ANOMALY, 2)).orElseThrow());
        assertEquals(0, repository.deleteMatching(filter(null, Set.of("temperature"), null, null, null, null)));
    }

    @Test
    void storedResultsSurviveReopeningAFileDatabase(@TempDir Path dir) {
        StorageConfig config = new StorageConfig("jdbc:h2:file:" + dir.resolve("ml-test").toAbsolutePath(), null, null, 0, 0);
        try (Database first = Database.open(config)) {
            JpaResultRepository stored = new JpaResultRepository(first);
            stored.record(anomalyEntity(anomaly(1, "temperature", "chain-a", 10, 14.2)));
            stored.addComment(ResultKind.ANOMALY, stored.search(ResultFilter.all(null), PageRequest.firstPage())
                    .items().get(0).id(), "persisted", null, at(20));
        }

        try (Database reopened = Database.open(config)) {
            Page<StoredResult> page = new JpaResultRepository(reopened).search(ResultFilter.all(null), PageRequest.firstPage());
            assertEquals(1, page.total());
            assertEquals("persisted", page.items().get(0).comments().get(0).text());
        }
    }

    @Test
    void unusableDatabaseIsReportedClearly() {
        StorageException e = assertThrows(StorageException.class,
                () -> Database.open(new StorageConfig("jdbc:nodriver:x", null, null, 0, 0)));

        assertTrue(e.getMessage().startsWith("Cannot open database jdbc:nodriver:x"), e.getMessage());
        assertNotNull(e.getCause());
    }
}
