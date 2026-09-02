package dev.fsp.app.persistence;

import dev.fsp.app.persistence.RunRecords.Checkpoint;
import dev.fsp.app.persistence.RunRecords.Run;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes runs and their checkpoints. */
@Repository
public class RunRepository {

    private final JdbcClient jdbc;

    public RunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Keeps callers that do not care about a description compiling. */
    public UUID create(UUID systemVersionId, String name, long seed, double speed, UUID parentRunId,
            Long forkedFromTick, long startingTick) {
        return create(systemVersionId, name, "", seed, speed, parentRunId, forkedFromTick, startingTick);
    }

    public UUID create(UUID systemVersionId, String name, String description, long seed, double speed,
            UUID parentRunId, Long forkedFromTick, long startingTick) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO simulation_run (id, system_version_id, name, description, seed, status, current_tick,
                                            speed_ticks_per_sec, parent_run_id, forked_from_tick)
                VALUES (:id, :versionId, :name, :description, :seed, 'CREATED', :tick, :speed, :parentId, :forkedFrom)
                """).param("id", id).param("versionId", systemVersionId).param("name", name)
                .param("description", description == null ? "" : description).param("seed", seed)
                .param("tick", startingTick).param("speed", speed).param("parentId", parentRunId)
                .param("forkedFrom", forkedFromTick).update();
        return id;
    }

    public Optional<Run> find(UUID id) {
        return jdbc.sql(SELECT_RUN + " WHERE r.id = :id").param("id", id).query(RunRepository::mapRun).optional();
    }

    /** Newest first, paged: an instance accumulates runs faster than one page can usefully show. */
    public List<Run> findAll(int limit, int offset) {
        return jdbc.sql(SELECT_RUN + " ORDER BY r.created_at DESC LIMIT :limit OFFSET :offset")
                .param("limit", limit).param("offset", offset).query(RunRepository::mapRun).list();
    }

    public long countAll() {
        return jdbc.sql("SELECT count(*) FROM simulation_run")
                .query((ResultSet rs, int rowNum) -> rs.getLong(1)).single();
    }

    public List<Run> findByStatus(RunStatus... statuses) {
        List<String> names = java.util.Arrays.stream(statuses).map(Enum::name).toList();
        return jdbc.sql(SELECT_RUN + " WHERE r.status = ANY (:statuses) ORDER BY r.created_at DESC")
                .param("statuses", names.toArray(String[]::new)).query(RunRepository::mapRun).list();
    }

    /** Runs forked from this one, so the UI can draw the what-if tree. */
    public List<Run> findChildren(UUID parentRunId) {
        return jdbc.sql(SELECT_RUN + " WHERE r.parent_run_id = :parentId ORDER BY r.created_at")
                .param("parentId", parentRunId).query(RunRepository::mapRun).list();
    }

    public void updateProgress(UUID id, long currentTick, RunStatus status) {
        jdbc.sql("""
                UPDATE simulation_run SET current_tick = :tick, status = :status, updated_at = now()
                WHERE id = :id
                """).param("id", id).param("tick", currentTick).param("status", status.name()).update();
    }

    public void updateStatus(UUID id, RunStatus status) {
        jdbc.sql("UPDATE simulation_run SET status = :status, updated_at = now() WHERE id = :id").param("id", id)
                .param("status", status.name()).update();
    }

    public void updateSpeed(UUID id, double speed) {
        jdbc.sql("UPDATE simulation_run SET speed_ticks_per_sec = :speed, updated_at = now() WHERE id = :id")
                .param("id", id).param("speed", speed).update();
    }

    public void markFailed(UUID id, String message) {
        jdbc.sql("""
                UPDATE simulation_run SET status = 'FAILED', error_message = :message, updated_at = now()
                WHERE id = :id
                """).param("id", id).param("message", message).update();
    }

    /** Sets what a run was for. Separate from renaming: a name is a label, this is the reason. */
    public void describe(UUID id, String description) {
        jdbc.sql("UPDATE simulation_run SET description = :description, updated_at = now() WHERE id = :id")
                .param("id", id).param("description", description == null ? "" : description).update();
    }

    public void describeCheckpoint(UUID id, String description) {
        jdbc.sql("UPDATE checkpoint SET description = :description WHERE id = :id")
                .param("id", id).param("description", description == null ? "" : description).update();
    }

    public void rename(UUID id, String name) {
        jdbc.sql("UPDATE simulation_run SET name = :name, updated_at = now() WHERE id = :id").param("id", id)
                .param("name", name).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM simulation_run WHERE id = :id").param("id", id).update();
    }

    // --- checkpoints ---------------------------------------------------------------------------

    public UUID saveCheckpoint(UUID runId, long tick, String label, byte[] state, boolean automatic) {
        return saveCheckpoint(runId, tick, label, "", state, automatic);
    }

    public UUID saveCheckpoint(UUID runId, long tick, String label, String description, byte[] state,
            boolean automatic) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO checkpoint (id, run_id, tick, label, description, state, state_bytes, automatic)
                VALUES (:id, :runId, :tick, :label, :description, :state, :bytes, :automatic)
                """).param("id", id).param("runId", runId).param("tick", tick).param("label", label)
                .param("description", description == null ? "" : description)
                .param("state", state).param("bytes", state.length).param("automatic", automatic).update();
        return id;
    }

    public List<Checkpoint> findCheckpoints(UUID runId) {
        return jdbc.sql("""
                SELECT id, run_id, tick, label, description, state_bytes, automatic, created_at
                FROM checkpoint WHERE run_id = :runId ORDER BY tick DESC
                """).param("runId", runId).query(RunRepository::mapCheckpoint).list();
    }

    public Optional<Checkpoint> findCheckpoint(UUID checkpointId) {
        return jdbc.sql("""
                SELECT id, run_id, tick, label, description, state_bytes, automatic, created_at
                FROM checkpoint WHERE id = :id
                """).param("id", checkpointId).query(RunRepository::mapCheckpoint).optional();
    }

    /** The most recent checkpoint of a run, which is where recovery resumes from. */
    public Optional<Checkpoint> findLatestCheckpoint(UUID runId) {
        return jdbc.sql("""
                SELECT id, run_id, tick, label, description, state_bytes, automatic, created_at
                FROM checkpoint WHERE run_id = :runId ORDER BY tick DESC LIMIT 1
                """).param("runId", runId).query(RunRepository::mapCheckpoint).optional();
    }

    public Optional<byte[]> loadCheckpointState(UUID checkpointId) {
        return jdbc.sql("SELECT state FROM checkpoint WHERE id = :id").param("id", checkpointId)
                .query((ResultSet rs, int rowNum) -> rs.getBytes("state")).optional();
    }

    public void deleteCheckpoint(UUID checkpointId) {
        jdbc.sql("DELETE FROM checkpoint WHERE id = :id").param("id", checkpointId).update();
    }

    /**
     * Discards older automatic checkpoints, keeping the newest few.
     *
     * <p>Automatic checkpoints exist to survive a crash, not to provide history; the ones a user
     * asked for are never touched. Without this a long run quietly accumulates gigabytes.
     */
    /** Removes every checkpoint of a run. Used to reproduce a hard stop, where none was written. */
    public int deleteAllCheckpoints(UUID runId) {
        return jdbc.sql("DELETE FROM checkpoint WHERE run_id = :runId").param("runId", runId).update();
    }

    public int pruneAutomaticCheckpoints(UUID runId, int keep) {
        return jdbc.sql("""
                DELETE FROM checkpoint WHERE id IN (
                    SELECT id FROM checkpoint
                    WHERE run_id = :runId AND automatic
                    ORDER BY tick DESC OFFSET :keep
                )
                """).param("runId", runId).param("keep", keep).update();
    }

    // The system's name comes from the join rather than from the version's spec column: listing
    // runs used to deserialise a full specification per row to read one string out of it.
    private static final String SELECT_RUN = """
            SELECT r.id, r.system_version_id, r.name, r.seed, r.status, r.current_tick, r.speed_ticks_per_sec,
                   r.parent_run_id, r.forked_from_tick, r.error_message, r.created_at, r.updated_at,
                   r.description, d.name AS system_name
            FROM simulation_run r
            JOIN system_version v ON v.id = r.system_version_id
            JOIN system_definition d ON d.id = v.system_id
            """;

    private static Run mapRun(ResultSet rs, int rowNum) throws SQLException {
        Long forkedFrom = rs.getObject("forked_from_tick", Long.class);
        return new Run(rs.getObject("id", UUID.class), rs.getObject("system_version_id", UUID.class),
                rs.getString("name"), rs.getLong("seed"), RunStatus.valueOf(rs.getString("status")),
                rs.getLong("current_tick"), rs.getDouble("speed_ticks_per_sec"),
                rs.getObject("parent_run_id", UUID.class), forkedFrom, rs.getString("error_message"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getString("system_name"),
                rs.getString("description"));
    }

    private static Checkpoint mapCheckpoint(ResultSet rs, int rowNum) throws SQLException {
        return new Checkpoint(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class), rs.getLong("tick"),
                rs.getString("label"), rs.getString("description"), rs.getInt("state_bytes"), rs.getBoolean("automatic"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
