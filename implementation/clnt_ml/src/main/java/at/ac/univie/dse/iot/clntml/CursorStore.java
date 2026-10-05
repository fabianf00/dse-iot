package at.ac.univie.dse.iot.clntml;

import at.ac.univie.dse.iot.common.json.JsonCodec;
import at.ac.univie.dse.iot.common.json.JsonCodecException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * Keeps the cursors of all endpoints in a small JSON file, so a later run (e.g. the next
 * {@code --once}) prints only what is new:
 * {@code {"cursors":{"ml-1":{"serverEpoch":1637412000000,"position":17}}}}.
 */
public final class CursorStore {

    /** File format. */
    record CursorFile(Map<String, PollCursor.Snapshot> cursors) {
    }

    private final Path file;
    private final JsonCodec json = JsonCodec.shared();

    public CursorStore(Path file) {
        this.file = file;
    }

    /**
     * Cursors by endpoint name; empty if the file does not exist yet.
     *
     * @throws IllegalStateException if the file exists but cannot be read
     */
    public Map<String, PollCursor.Snapshot> load() {
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            CursorFile content = json.fromJson(Files.readString(file), CursorFile.class);
            return content.cursors() == null ? Map.of() : content.cursors();
        } catch (IOException | JsonCodecException e) {
            throw new IllegalStateException("cannot read cursor file " + file + ": " + e.getMessage(), e);
        }
    }

    /** Writes the cursors atomically (temporary file, then rename). */
    public void save(Map<String, PollCursor.Snapshot> cursors) throws IOException {
        Path absolute = file.toAbsolutePath();
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        Files.writeString(temporary, json.toJson(new CursorFile(new TreeMap<>(cursors))));
        Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
