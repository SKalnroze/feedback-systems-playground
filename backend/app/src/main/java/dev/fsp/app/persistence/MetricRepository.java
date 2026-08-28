package dev.fsp.app.persistence;

import dev.fsp.app.persistence.SeriesRecords.Point;
import dev.fsp.app.persistence.SeriesRecords.Series;
import dev.fsp.app.persistence.SeriesRecords.SeriesDefinition;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The time-series store.
 *
 * <p>Everything here is shaped by one fact: samples outnumber every other row in the database by
 * several orders of magnitude. Series keys are interned so a sample row carries an integer rather
 * than a string, writes go through JDBC batches rather than one statement per point, and any query
 * spanning more ticks than it can usefully draw is answered from pre-aggregated buckets instead of
 * raw rows.
 */
@Repository
public class MetricRepository {

    /** Points beyond which a chart gains nothing from more resolution, so bucketing kicks in. */
    public static final int MAX_POINTS_PER_SERIES = 2_000;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public MetricRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /**
     * Finds or creates the numeric id for a series key.
     *
     * <p>Concurrent inserts are possible when two runs start at once, so the insert tolerates a
     * conflict and re-reads rather than failing.
     */
    public long internSeries(UUID runId, String seriesKey, String objectId, String variable, String category) {
        jdbc.sql("""
                INSERT INTO metric_series (run_id, series_key, object_id, variable, category)
                VALUES (:runId, :key, :objectId, :variable, :category)
                ON CONFLICT (run_id, series_key) DO NOTHING
                """).param("runId", runId).param("key", seriesKey).param("objectId", objectId)
                .param("variable", variable).param("category", category).update();

        return jdbc.sql("SELECT id FROM metric_series WHERE run_id = :runId AND series_key = :key")
                .param("runId", runId).param("key", seriesKey)
                .query((ResultSet rs, int rowNum) -> rs.getLong("id")).single();
    }

    public List<SeriesDefinition> findSeries(UUID runId) {
        return jdbc.sql("""
                SELECT id, series_key, object_id, variable, category
                FROM metric_series WHERE run_id = :runId ORDER BY series_key
                """).param("runId", runId)
                .query((ResultSet rs, int rowNum) -> new SeriesDefinition(rs.getLong("id"), rs.getString("series_key"),
                        rs.getString("object_id"), rs.getString("variable"), rs.getString("category")))
                .list();
    }

    /** Writes a batch of samples. Duplicate ticks are ignored, which makes a re-flush harmless. */
    public void insertSamples(UUID runId, List<SampleRow> samples) {
        if (samples.isEmpty()) {
            return;
        }
        template.batchUpdate("""
                INSERT INTO metric_sample (run_id, series_id, tick, value)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (run_id, series_id, tick) DO NOTHING
                """, samples, samples.size(), (PreparedStatement ps, SampleRow row) -> {
            ps.setObject(1, runId);
            ps.setLong(2, row.seriesId());
            ps.setLong(3, row.tick());
            ps.setDouble(4, row.value());
        });
    }

    /**
     * Fetches one series over a tick range, bucketing when the range is too wide to draw raw.
     *
     * @param resolution requested ticks per point, or 0 to let the range decide
     */
    public Series fetchSeries(UUID runId, String seriesKey, long fromTick, long toTick, int resolution) {
        return fetchSeries(runId, seriesKey, fromTick, toTick, resolution, 0);
    }

    /**
     * @param rollupBucketTicks the bucket size rollups were built at, or 0 if there are none; a
     *                          request at exactly that size is served from them instead of from raw
     *                          rows, which is what keeps a chart of a million-tick run responsive
     */
    public Series fetchSeries(UUID runId, String seriesKey, long fromTick, long toTick, int resolution,
            int rollupBucketTicks) {
        long span = Math.max(1, toTick - fromTick);
        int bucketSize = resolution > 0 ? resolution : (int) Math.max(1, span / MAX_POINTS_PER_SERIES);

        if (rollupBucketTicks > 1 && bucketSize == rollupBucketTicks) {
            List<Point> rolled = fetchFromRollups(runId, seriesKey, fromTick, toTick, rollupBucketTicks);
            if (!rolled.isEmpty()) {
                return new Series(seriesKey, rolled, rollupBucketTicks, true);
            }
        }

        if (bucketSize <= 1) {
            List<Point> points = jdbc.sql("""
                    SELECT s.tick, s.value
                    FROM metric_sample s JOIN metric_series d ON d.id = s.series_id
                    WHERE s.run_id = :runId AND d.series_key = :key AND s.tick BETWEEN :from AND :to
                    ORDER BY s.tick
                    """).param("runId", runId).param("key", seriesKey).param("from", fromTick).param("to", toTick)
                    .query((ResultSet rs, int rowNum) -> new Point(rs.getLong("tick"), rs.getDouble("value"))).list();
            return new Series(seriesKey, points, 1, false);
        }

        // Bucketing in SQL rather than in Java: the point of downsampling is to avoid moving the
        // raw rows across the wire at all.
        List<Point> points = jdbc.sql("""
                SELECT (s.tick / :bucket) * :bucket AS bucket_tick, avg(s.value) AS value
                FROM metric_sample s JOIN metric_series d ON d.id = s.series_id
                WHERE s.run_id = :runId AND d.series_key = :key AND s.tick BETWEEN :from AND :to
                GROUP BY bucket_tick
                ORDER BY bucket_tick
                """).param("runId", runId).param("key", seriesKey).param("from", fromTick).param("to", toTick)
                .param("bucket", (long) bucketSize)
                .query((ResultSet rs, int rowNum) -> new Point(rs.getLong("bucket_tick"), rs.getDouble("value")))
                .list();
        return new Series(seriesKey, points, bucketSize, true);
    }

    private List<Point> fetchFromRollups(UUID runId, String seriesKey, long fromTick, long toTick,
            int bucketSize) {
        return jdbc.sql("""
                SELECT r.bucket_tick, r.avg_value
                FROM metric_rollup r JOIN metric_series d ON d.id = r.series_id
                WHERE r.run_id = :runId AND d.series_key = :key AND r.bucket_size = :bucket
                  AND r.bucket_tick BETWEEN :from AND :to
                ORDER BY r.bucket_tick
                """).param("runId", runId).param("key", seriesKey).param("bucket", bucketSize)
                .param("from", fromTick).param("to", toTick)
                .query((ResultSet rs, int rowNum) -> new Point(rs.getLong("bucket_tick"),
                        rs.getDouble("avg_value")))
                .list();
    }

    /** Fetches several series at once, which is what a multi-series chart panel actually needs. */
    public Map<String, Series> fetchSeries(UUID runId, List<String> seriesKeys, long fromTick, long toTick,
            int resolution) {
        return fetchSeries(runId, seriesKeys, fromTick, toTick, resolution, 0);
    }

    public Map<String, Series> fetchSeries(UUID runId, List<String> seriesKeys, long fromTick, long toTick,
            int resolution, int rollupBucketTicks) {
        Map<String, Series> result = new LinkedHashMap<>();
        for (String key : seriesKeys) {
            result.put(key, fetchSeries(runId, key, fromTick, toTick, resolution, rollupBucketTicks));
        }
        return result;
    }

    /** Highest tick with a stored sample, used to bound a chart's default range. */
    public long latestSampledTick(UUID runId) {
        Long tick = jdbc.sql("SELECT max(tick) FROM metric_sample WHERE run_id = :runId").param("runId", runId)
                .query((ResultSet rs, int rowNum) -> rs.getObject(1, Long.class)).optional().orElse(null);
        return tick == null ? 0L : tick;
    }

    /**
     * Rolls raw samples up into fixed buckets for the range given.
     *
     * <p>Run after a range is complete rather than continuously: buckets built from a partially
     * written range would have to be rewritten as later samples arrive.
     */
    public int buildRollups(UUID runId, int bucketSize, long fromTick, long toTick) {
        // The bucket expression is computed in a subquery and grouped by its alias. Repeating
        // it in both SELECT and GROUP BY does not work: each occurrence of a named parameter
        // becomes a separate placeholder, so Postgres sees two different expressions and rejects
        // the statement.
        return jdbc.sql("""
                INSERT INTO metric_rollup (run_id, series_id, bucket_tick, bucket_size, avg_value, min_value,
                                           max_value, sample_count)
                SELECT run_id, series_id, bucket_tick, :bucket,
                       avg(value), min(value), max(value), count(*)
                FROM (
                    SELECT run_id, series_id, (tick / :bucket) * :bucket AS bucket_tick, value
                    FROM metric_sample
                    WHERE run_id = :runId AND tick BETWEEN :from AND :to
                ) bucketed
                GROUP BY run_id, series_id, bucket_tick
                ON CONFLICT (run_id, series_id, bucket_size, bucket_tick) DO UPDATE
                SET avg_value = EXCLUDED.avg_value, min_value = EXCLUDED.min_value,
                    max_value = EXCLUDED.max_value, sample_count = EXCLUDED.sample_count
                """).param("runId", runId).param("bucket", (long) bucketSize).param("from", fromTick)
                .param("to", toTick).update();
    }

    public void deleteSamples(UUID runId) {
        jdbc.sql("DELETE FROM metric_sample WHERE run_id = :runId").param("runId", runId).update();
        jdbc.sql("DELETE FROM metric_rollup WHERE run_id = :runId").param("runId", runId).update();
    }

    /** Number of stored samples, shown in the UI so a run's storage cost is not a surprise. */
    public long countSamples(UUID runId) {
        return jdbc.sql("SELECT count(*) FROM metric_sample WHERE run_id = :runId").param("runId", runId)
                .query((ResultSet rs, int rowNum) -> rs.getLong(1)).single();
    }

    /** One value of one series at one tick, ready for batch insertion. */
    public record SampleRow(long seriesId, long tick, double value) {
    }

    /** Collects rows for a flush without the caller having to know the batch shape. */
    public static List<SampleRow> rows(Map<Long, Double> valuesBySeriesId, long tick) {
        List<SampleRow> rows = new ArrayList<>(valuesBySeriesId.size());
        valuesBySeriesId.forEach((seriesId, value) -> rows.add(new SampleRow(seriesId, tick, value)));
        return rows;
    }
}
