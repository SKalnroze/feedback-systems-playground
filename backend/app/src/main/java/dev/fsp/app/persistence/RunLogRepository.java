package dev.fsp.app.persistence;

import dev.fsp.app.persistence.RunRecords.LogEntry;
import dev.fsp.app.persistence.RunRecords.MemorySnapshot;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The audit trail and the memory inspector's backing store.
 *
 * <p>A chart shows that resentment jumped at tick 412; this is what lets the user find out that it
 * was a conflict, between whom, and which remembered grievance triggered it. Without it the
 * observability story stops at "something happened".
 */
@Repository
public class RunLogRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public RunLogRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public void append(UUID runId, List<LogRow> entries) {
        if (entries.isEmpty()) {
            return;
        }
        template.batchUpdate("""
                INSERT INTO run_event_log (run_id, tick, entry_type, subject, detail)
                VALUES (?, ?, ?, ?, ?)
                """, entries, entries.size(), (PreparedStatement ps, LogRow row) -> {
            ps.setObject(1, runId);
            ps.setLong(2, row.tick());
            ps.setString(3, row.entryType());
            ps.setString(4, row.subject());
            ps.setString(5, row.detail());
        });
    }

    /**
     * Removes log entries after a tick, for a run that has been rewound.
     *
     * <p>The log is the record of why a run looks the way it does; entries describing events that
     * have since been undone would make it a record of something else.
     */
    public int deleteAfter(UUID runId, long tick) {
        return jdbc.sql("DELETE FROM run_event_log WHERE run_id = :runId AND tick > :tick")
                .param("runId", runId).param("tick", tick).update();
    }

    public void appendOne(UUID runId, long tick, String entryType, String subject, String detail) {
        append(runId, List.of(new LogRow(tick, entryType, subject, detail)));
    }

    /**
     * Log entries in a tick range, newest first.
     *
     * @param entryType filter, or null for everything
     */
    public List<LogEntry> find(UUID runId, Long fromTick, Long toTick, String entryType, int limit) {
        return jdbc.sql("""
                SELECT id, run_id, tick, entry_type, subject, detail
                FROM run_event_log
                WHERE run_id = :runId
                  AND (CAST(:fromTick AS bigint) IS NULL OR tick >= :fromTick)
                  AND (CAST(:toTick AS bigint) IS NULL OR tick <= :toTick)
                  AND (CAST(:entryType AS text) IS NULL OR entry_type = :entryType)
                ORDER BY tick DESC, id DESC
                LIMIT :limit
                """).param("runId", runId).param("fromTick", fromTick).param("toTick", toTick)
                .param("entryType", entryType).param("limit", limit).query(RunLogRepository::mapLog).list();
    }

    public long countEntries(UUID runId) {
        return jdbc.sql("SELECT count(*) FROM run_event_log WHERE run_id = :runId").param("runId", runId)
                .query((ResultSet rs, int rowNum) -> rs.getLong(1)).single();
    }

    // --- memories ------------------------------------------------------------------------------

    /**
     * Replaces the stored memory snapshot for a run.
     *
     * <p>Overwritten wholesale on each flush rather than accumulated: this table answers "what does
     * this person currently remember", and keeping every historical version of every memory would
     * dwarf even the sample table. The history that matters is in the series and the log.
     */
    public void replaceMemories(UUID runId, List<MemoryRow> memories) {
        jdbc.sql("DELETE FROM memory_record WHERE run_id = :runId").param("runId", runId).update();
        if (memories.isEmpty()) {
            return;
        }
        template.batchUpdate("""
                INSERT INTO memory_record (id, run_id, owner_id, subject_id, kind, created_tick, origin_tick,
                                           initial_strength, current_strength, valence, salience,
                                           reactivation_count, last_sampled_tick)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, memories, Math.min(memories.size(), 500), (PreparedStatement ps, MemoryRow row) -> {
            ps.setLong(1, row.id());
            ps.setObject(2, runId);
            ps.setString(3, row.ownerId());
            ps.setString(4, row.subjectId());
            ps.setString(5, row.kind());
            ps.setLong(6, row.createdTick());
            ps.setLong(7, row.originTick());
            ps.setDouble(8, row.initialStrength());
            ps.setDouble(9, row.currentStrength());
            ps.setDouble(10, row.valence());
            ps.setDouble(11, row.salience());
            ps.setInt(12, row.reactivationCount());
            ps.setLong(13, row.sampledTick());
        });
    }

    /** Memories held by one object, strongest first. */
    public List<MemorySnapshot> findMemories(UUID runId, String ownerId, int limit) {
        return jdbc.sql("""
                SELECT id, run_id, owner_id, subject_id, kind, created_tick, origin_tick, initial_strength,
                       current_strength, valence, salience, reactivation_count, last_sampled_tick, forgotten_at_tick
                FROM memory_record
                WHERE run_id = :runId AND (CAST(:ownerId AS text) IS NULL OR owner_id = :ownerId)
                ORDER BY current_strength DESC, id
                LIMIT :limit
                """).param("runId", runId).param("ownerId", ownerId).param("limit", limit)
                .query(RunLogRepository::mapMemory).list();
    }

    /**
     * How each object currently regards each other object, as a strength-weighted mean valence.
     * This is what the relationship heatmap draws.
     */
    public List<RelationshipCell> relationshipMatrix(UUID runId) {
        return jdbc.sql("""
                SELECT owner_id, subject_id,
                       sum(current_strength * valence) AS weighted_valence,
                       sum(current_strength) AS total_strength,
                       count(*) AS memory_count
                FROM memory_record
                WHERE run_id = :runId
                GROUP BY owner_id, subject_id
                ORDER BY owner_id, subject_id
                """).param("runId", runId)
                .query((ResultSet rs, int rowNum) -> new RelationshipCell(rs.getString("owner_id"),
                        rs.getString("subject_id"), rs.getDouble("weighted_valence"),
                        rs.getDouble("total_strength"), rs.getInt("memory_count")))
                .list();
    }

    private static LogEntry mapLog(ResultSet rs, int rowNum) throws SQLException {
        return new LogEntry(rs.getLong("id"), rs.getObject("run_id", UUID.class), rs.getLong("tick"),
                rs.getString("entry_type"), rs.getString("subject"), rs.getString("detail"));
    }

    private static MemorySnapshot mapMemory(ResultSet rs, int rowNum) throws SQLException {
        return new MemorySnapshot(rs.getLong("id"), rs.getObject("run_id", UUID.class), rs.getString("owner_id"),
                rs.getString("subject_id"), rs.getString("kind"), rs.getLong("created_tick"),
                rs.getLong("origin_tick"), rs.getDouble("initial_strength"), rs.getDouble("current_strength"),
                rs.getDouble("valence"), rs.getDouble("salience"), rs.getInt("reactivation_count"),
                rs.getLong("last_sampled_tick"), rs.getObject("forgotten_at_tick", Long.class));
    }

    /** A pending log line. */
    public record LogRow(long tick, String entryType, String subject, String detail) {
    }

    /** A memory as written to the inspector table. */
    public record MemoryRow(long id, String ownerId, String subjectId, String kind, long createdTick, long originTick,
            double initialStrength, double currentStrength, double valence, double salience, int reactivationCount,
            long sampledTick) {
    }

    /**
     * One cell of the relationship matrix.
     *
     * @param weightedValence sum of valence weighted by strength; positive means well regarded
     * @param totalStrength   how much is remembered at all, which is confidence in that reading
     */
    public record RelationshipCell(String ownerId, String subjectId, double weightedValence, double totalStrength,
            int memoryCount) {

        /** Mean feeling, or zero when nothing is remembered. */
        public double meanValence() {
            return totalStrength == 0.0 ? 0.0 : weightedValence / totalStrength;
        }
    }
}
