package dev.fsp.app.run;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns checkpoints into bytes and back.
 *
 * <p>Gzipped JSON rather than a binary format: a checkpoint of a long run is mostly repeated field
 * names and small numbers, which compresses by an order of magnitude, and keeping it readable after
 * decompression is worth a great deal the first time a resumed run does not match.
 */
@Component
public class SnapshotCodec {

    private final ObjectMapper json;

    public SnapshotCodec(ObjectMapper json) {
        this.json = json;
    }

    public byte[] encode(EngineSnapshot snapshot) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                json.writeValue(gzip, snapshot);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not write checkpoint", e);
        }
    }

    public EngineSnapshot decode(byte[] payload) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
            EngineSnapshot snapshot = json.readValue(gzip, EngineSnapshot.class);
            if (snapshot.formatVersion() > EngineSnapshot.CURRENT_FORMAT) {
                throw new IllegalStateException("checkpoint was written by a newer version of the application");
            }
            return snapshot;
        } catch (IOException e) {
            throw new IllegalStateException("could not read checkpoint", e);
        }
    }
}
