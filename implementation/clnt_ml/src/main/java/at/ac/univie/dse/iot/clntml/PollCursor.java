package at.ac.univie.dse.iot.clntml;

/**
 * Where the client stands in the event log of one ML instance: the server epoch it belongs to
 * and the sequence number of the last event shown. Epoch 0 means unknown (nothing polled yet).
 */
public final class PollCursor {

    /** Serializable state, e.g. for the cursor file. */
    public record Snapshot(long serverEpoch, long position) {
    }

    private long serverEpoch;
    private long position;

    public PollCursor() {
        this(new Snapshot(0, 0));
    }

    public PollCursor(Snapshot snapshot) {
        this.serverEpoch = snapshot.serverEpoch();
        this.position = snapshot.position();
    }

    public synchronized long position() {
        return position;
    }

    /**
     * Adopts the epoch of an answer. If it differs from a known epoch, the service restarted and its
     * sequence numbers start again: the position is reset to 0.
     *
     * @return {@code true} if the position was reset because of a restart
     */
    public synchronized boolean adoptEpoch(long epoch) {
        if (epoch == serverEpoch) {
            return false;
        }
        boolean restarted = serverEpoch != 0;
        serverEpoch = epoch;
        position = 0;
        return restarted;
    }

    public synchronized void advanceTo(long nextCursor) {
        position = nextCursor;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(serverEpoch, position);
    }
}
