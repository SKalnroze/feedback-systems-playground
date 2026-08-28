package dev.fsp.app.persistence;

import dev.fsp.engine.spec.SystemSpec;
import java.time.Instant;
import java.util.UUID;

/** Rows of the definition tables, as the rest of the application sees them. */
public final class SystemRecords {

    private SystemRecords() {
    }

    /**
     * A system as a user thinks of it: a name, a working draft, and a history of published
     * versions.
     *
     * @param draft the editor's working copy, or null for a system that has never been edited
     */
    public record SystemDefinition(UUID id, String name, String description, SystemSpec draft, Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * An immutable published snapshot. Runs point at one of these rather than at the system, so
     * editing a system can never change what a completed run meant.
     */
    public record SystemVersion(UUID id, UUID systemId, int version, SystemSpec spec, String checksum,
            Instant publishedAt) {
    }
}
