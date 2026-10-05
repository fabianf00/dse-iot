package at.ac.univie.dse.iot.mlapi;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Health and meta information of an MS ML instance.
 *
 * @param serverEpoch       same value as in {@link PollResult#serverEpoch()}
 * @param dataTypes         data types the service has received data for
 * @param fogStatus         state of the subscription at each fog node, by fog id, e.g. {@code ACTIVE}
 * @param queueSize         jobs waiting for a worker
 * @param droppedJobs       jobs dropped because the queue was full
 * @param lastEventSequence sequence number of the newest event, 0 if there is none
 */
public record ServiceStatusDto(String serviceId, long serverEpoch, Instant startedAt, List<String> dataTypes,
        Map<String, String> fogStatus, int queueSize, long droppedJobs, long lastEventSequence) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ServiceStatusDto {
        dataTypes = List.copyOf(dataTypes);
        fogStatus = Map.copyOf(fogStatus);
    }
}
