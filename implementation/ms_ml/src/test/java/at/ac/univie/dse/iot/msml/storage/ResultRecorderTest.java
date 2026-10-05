package at.ac.univie.dse.iot.msml.storage;

import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.anomaly;
import static at.ac.univie.dse.iot.msml.storage.StorageFixtures.prediction;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import at.ac.univie.dse.iot.common.concurrent.NamedThreadFactory;
import at.ac.univie.dse.iot.msml.storage.PageRequest.SortOrder;
import at.ac.univie.dse.iot.msml.storage.StoredResult.StoredComment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResultRecorderTest {

    private static final Instant NOW = Instant.parse("2021-11-20T15:00:00Z");

    private final Database database = StorageFixtures.memoryDatabase();
    private final JpaResultRepository repository = new JpaResultRepository(database);
    private ResultRecorder recorder;

    @AfterEach
    void close() {
        if (recorder != null) {
            recorder.stop(Duration.ofSeconds(1));
        }
        database.close();
    }

    private ResultRecorder recorder(ResultRepository target, int capacity) {
        return new ResultRecorder("ml-1", 77, target, capacity, Clock.fixed(NOW, ZoneOffset.UTC),
                new NamedThreadFactory("test-recorder", true));
    }

    @Test
    void eventsAreStoredWithInstanceEpochAndRecordingTime() {
        recorder = recorder(repository, 10);
        recorder.start();

        recorder.accept(anomaly(1, "temperature", "temp-sudden-drop", 10, 14.2));
        recorder.accept(prediction(2, "co2", 20, 500));

        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.recordedCount() == 2);
        List<StoredResult> stored = repository.search(ResultFilter.all(null), new PageRequest(10, 0, SortOrder.OLDEST_FIRST))
                .items();
        assertInstanceOf(StoredResult.StoredAnomaly.class, stored.get(0));
        assertInstanceOf(StoredResult.StoredPrediction.class, stored.get(1));
        assertEquals("ml-1", stored.get(0).mlInstance());
        assertEquals(77, stored.get(0).serverEpoch());
        assertEquals(NOW, stored.get(0).recordedAt());
    }

    @Test
    void sameEventTwiceIsCountedAsDuplicate() {
        recorder = recorder(repository, 10);
        recorder.start();

        recorder.accept(anomaly(1, "temperature", "temp-sudden-drop", 10, 14.2));
        recorder.accept(anomaly(1, "temperature", "temp-sudden-drop", 10, 14.2));

        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.duplicateCount() == 1);
        assertEquals(1, recorder.recordedCount());
        assertEquals(1, repository.count(ResultFilter.all(null)));
    }

    @Test
    void fullQueueIsCountedNotThrown() {
        recorder = recorder(repository, 1);

        recorder.accept(anomaly(1, "temperature", "temp-sudden-drop", 10, 14.2));
        recorder.accept(anomaly(2, "temperature", "temp-sudden-drop", 20, 14.2));

        assertEquals(1, recorder.failedCount());
        assertEquals(1, recorder.queueSize());
    }

    @Test
    void databaseErrorsAreCountedAndRecordingContinues() {
        ResultRepository failingOnce = new DelegatingRepository(repository) {
            private boolean failed;

            @Override
            public boolean record(MlResultEntity result) {
                if (!failed) {
                    failed = true;
                    throw new StorageException("database down", null);
                }
                return super.record(result);
            }
        };
        recorder = recorder(failingOnce, 10);
        recorder.start();

        recorder.accept(anomaly(1, "temperature", "temp-sudden-drop", 10, 14.2));
        recorder.accept(anomaly(2, "temperature", "temp-sudden-drop", 20, 14.2));

        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.recordedCount() == 1);
        assertEquals(1, recorder.failedCount());
    }

    @Test
    void stopWritesQueuedEventsFirst() {
        recorder = recorder(repository, 100);
        for (int i = 1; i <= 50; i++) {
            recorder.accept(prediction(i, "co2", i, 500));
        }

        recorder.start();
        recorder.stop(Duration.ofSeconds(10));

        assertEquals(50, recorder.recordedCount());
        assertEquals(50, repository.count(ResultFilter.all(null)));
    }

    /** Forwards everything to another repository; tests override single methods. */
    private static class DelegatingRepository implements ResultRepository {
        private final ResultRepository delegate;

        DelegatingRepository(ResultRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean record(MlResultEntity result) {
            return delegate.record(result);
        }

        @Override
        public Optional<StoredResult> find(ResultKind kind, long id) {
            return delegate.find(kind, id);
        }

        @Override
        public Page<StoredResult> search(ResultFilter filter, PageRequest page) {
            return delegate.search(filter, page);
        }

        @Override
        public long count(ResultFilter filter) {
            return delegate.count(filter);
        }

        @Override
        public boolean delete(ResultKind kind, long id) {
            return delegate.delete(kind, id);
        }

        @Override
        public long deleteMatching(ResultFilter filter) {
            return delegate.deleteMatching(filter);
        }

        @Override
        public Optional<List<StoredComment>> comments(ResultKind kind, long resultId) {
            return delegate.comments(kind, resultId);
        }

        @Override
        public Optional<StoredComment> addComment(ResultKind kind, long resultId, String text, String author,
                Instant now) {
            return delegate.addComment(kind, resultId, text, author, now);
        }

        @Override
        public Optional<StoredComment> updateComment(long commentId, String text, Instant now) {
            return delegate.updateComment(commentId, text, now);
        }

        @Override
        public boolean deleteComment(long commentId) {
            return delegate.deleteComment(commentId);
        }
    }
}
