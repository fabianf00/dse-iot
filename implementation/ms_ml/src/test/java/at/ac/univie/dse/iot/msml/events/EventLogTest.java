package at.ac.univie.dse.iot.msml.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.ac.univie.dse.iot.common.model.DataType;
import at.ac.univie.dse.iot.common.model.Unit;
import at.ac.univie.dse.iot.msml.prediction.Prediction;
import at.ac.univie.dse.iot.msml.prediction.PredictionChange;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class EventLogTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2021-11-20T14:00:00Z"), ZoneOffset.UTC);
    private static final DataType CO2 = DataType.of("co2");

    private static PredictionChange change(double lower) {
        return new PredictionChange(CO2, null, new Prediction(CO2, Unit.PPM, lower, lower + 50, lower + 25, 1, 1));
    }

    private static EventLog logWith(int retention, int events) {
        EventLog log = new EventLog(retention, CLOCK);
        for (int i = 0; i < events; i++) {
            log.append(change(i * 50));
        }
        return log;
    }

    @Test
    void eventsGetConsecutiveSequenceNumbers() {
        EventLog log = new EventLog(10, CLOCK);

        assertEquals(1, log.append(change(0)).sequence());
        assertEquals(2, log.append(change(50)).sequence());
        assertEquals(2, log.lastSequence());
        assertEquals(CLOCK.millis(), log.epoch());
    }

    @Test
    void sinceReturnsEventsAfterTheCursor() {
        EventLog log = logWith(10, 5);

        EventLog.Page page = log.since(2, 100);

        assertEquals(3, page.events().size());
        assertEquals(3, page.events().get(0).sequence());
        assertEquals(5, page.nextCursor());
        assertFalse(page.truncated());
        assertFalse(page.hasMore());
    }

    @Test
    void emptyPageKeepsTheCursor() {
        EventLog log = logWith(10, 5);

        EventLog.Page page = log.since(5, 100);

        assertTrue(page.events().isEmpty());
        assertEquals(5, page.nextCursor());
        assertEquals(0, new EventLog(10, CLOCK).since(0, 10).nextCursor());
    }

    @Test
    void pagesAreLimitedAndReportMore() {
        EventLog log = logWith(10, 5);

        EventLog.Page first = log.since(0, 2);
        EventLog.Page second = log.since(first.nextCursor(), 2);
        EventLog.Page third = log.since(second.nextCursor(), 2);

        assertEquals(2, first.nextCursor());
        assertTrue(first.hasMore());
        assertEquals(4, second.nextCursor());
        assertTrue(second.hasMore());
        assertEquals(1, third.events().size());
        assertFalse(third.hasMore());
    }

    @Test
    void clientBehindTheRetentionIsToldEventsWereSkipped() {
        EventLog log = logWith(3, 10);

        EventLog.Page page = log.since(2, 100);

        assertTrue(page.truncated());
        assertEquals(8, page.events().get(0).sequence());
        assertFalse(log.since(7, 100).truncated());
    }

    @Test
    void retentionMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new EventLog(0, CLOCK));
    }
}
