package dev.fsp.app.persistence;

import java.time.Instant;
import java.util.UUID;

/** Rows of the run tables. */
public final class RunRecords {

    private RunRecords() {
    }

    /**
     * @param parentRunId    set when this run was forked from another run's checkpoint
     * @param forkedFromTick the tick the fork started at
     */
    public record Run(UUID id, UUID systemVersionId, String name, long seed, RunStatus status, long currentTick,
            double speedTicksPerSecond, UUID parentRunId, Long forkedFromTick, String errorMessage, Instant createdAt,
            Instant updatedAt) {
    }

    /** @param stateBytes compressed size, shown in the UI so long runs' storage cost is visible */
    public record Checkpoint(UUID id, UUID runId, long tick, String label, int stateBytes, boolean automatic,
            Instant createdAt) {
    }

    /** One line of the run's audit trail. */
    public record LogEntry(long id, UUID runId, long tick, String entryType, String subject, String detail) {
    }

    /** A memory as stored for the inspector. */
    public record MemorySnapshot(long id, UUID runId, String ownerId, String subjectId, String kind, long createdTick,
            long originTick, double initialStrength, double currentStrength, double valence, double salience,
            int reactivationCount, long lastSampledTick, Long forgottenAtTick) {
    }
}
