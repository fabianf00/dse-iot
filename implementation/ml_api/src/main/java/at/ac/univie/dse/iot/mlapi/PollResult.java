package at.ac.univie.dse.iot.mlapi;

import java.io.Serializable;
import java.util.List;

/**
 * Answer of {@link MlRemoteService#pollEvents}.
 *
 * @param serverEpoch identifies the running service instance; changes when it restarts
 * @param events      events after the requested cursor, oldest first
 * @param nextCursor  cursor for the next poll: the sequence of the last returned event, or the
 *                    requested cursor if there were none
 * @param truncated   {@code true} if events after the requested cursor were already discarded
 *                    because of the service's retention limit (the client fell behind)
 * @param hasMore     {@code true} if more events are available than were returned; poll again at once
 */
public record PollResult(long serverEpoch, List<MlEventDto> events, long nextCursor, boolean truncated, boolean hasMore)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    public PollResult {
        events = List.copyOf(events);
    }
}
