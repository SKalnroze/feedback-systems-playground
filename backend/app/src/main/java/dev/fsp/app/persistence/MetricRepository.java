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
        return fetchSeries(runId, List.of(seriesKey), fromTick, toTick, resolution, 0)
                .getOrDefault(seriesKey, new Series(seriesKey, List.of(), 1, false));
    }

    /** Fetches several series at once, which is what a multi-series chart panel actually needs. */
    public Map<String, Series> fetchSeries(UUID runId, List<String> seriesKeys, long fromTick, long toTick,
            int resolution) {
        return fetchSeries(runId, seriesKeys, fromTick, toTick, resolution, 0);
    }

    /**
     * Fetches several series in one query.
     *
     * <p>One statement for the whole panel rather than one per series: a panel showing eight
     * variables was previously eight round trips to Postgres, all of them scanning the same rows of
     * the same partition.
     *
     * @param rollupBucketTicks the bucket size rollups were built at, or 0 if there are none. Any
     *                          request whose bucket is a whole multiple of it can be answered from
     *                          rollups by aggregating them further, which is what keeps a chart of
     *                          a million-tick run responsive.
     */
    public Map<String, Series> fetchSeries(UUID runId, List<String> seriesKeys, long fromTick, long toTick,
            int resolution, int rollupBucketTicks) {
        Map<String, Series> result = new LinkedHashMap<>();
        if (seriesKeys.isEmpty()) {
            return result;
        }
        long span = Math.max(1, toTick - fromTick);
        int bucketSize = chooseBucket(resolution, span, rollupBucketTicks);

        Map<String, List<Point>> points;
        boolean bucketed;
        if (rollupBucketTicks > 1 && bucketSize >= rollupBucketTicks && bucketSize % rollupBucketTicks == 0) {
            points = fetchFromRollups(runId, seriesKeys, fromTick, toTick, rollupBucketTicks, bucketSize);
            bucketed = true;
            if (points.isEmpty()) {
                // Rollups had not been built for this range yet; fall back rather than draw nothing.
                points = fetchRaw(runId, seriesKeys, fromTick, toTick, bucketSize);
                bucketed = bucketSize > 1;
            }
        } else {
            points = fetchRaw(runId, seriesKeys, fromTick, toTick, bucketSize);
            bucketed = bucketSize > 1;
        }

        for (String key : seriesKeys) {
            result.put(key, new Series(key, points.getOrDefault(key, List.of()), bucketSize, bucketed));
        }
        return result;
    }

    /**
     * Picks ticks-per-point for a range.
     *
     * <p>An automatically chosen bucket is snapped up to a multiple of the rollup grid. A bucket of
     * 43 against a grid of 50 would be honest but would force a scan of the raw rows; 50 answers
     * the same question from the pre-aggregated ones.
     */
    private static int chooseBucket(int resolution, long span, int rollupBucketTicks) {
        if (resolution > 0) {
            return resolution;
        }
        int natural = (int) Math.max(1, span / MAX_POINTS_PER_SERIES);
        if (rollupBucketTicks > 1 && natural > 1) {
            int multiples = Math.max(1, (natural + rollupBucketTicks - 1) / rollupBucketTicks);
            return multiples * rollupBucketTicks;
        }
        return natural;
    }

    private Map<String, List<Point>> fetchRaw(UUID runId, List<String> seriesKeys, long fromTick, long toTick,
            int bucketSize) {
        String sql = bucketSize <= 1 ? """
                SELECT d.series_key, s.tick AS bucket_tick, s.value
                FROM metric_sample s JOIN metric_series d ON d.id = s.series_id
                WHERE s.run_id = :runId AND d.series_key IN (:keys) AND s.tick BETWEEN :from AND :to
                ORDER BY d.series_key, bucket_tick
                """ : """
                SELECT series_key, bucket_tick, avg(value) AS value
                FROM (
                    SELECT d.series_key, (s.tick / :bucket) * :bucket AS bucket_tick, s.value
                    FROM metric_sample s JOIN metric_series d ON d.id = s.series_id
                    WHERE s.run_id = :runId AND d.series_key IN (:keys) AND s.tick BETWEEN :from AND :to
                ) bucketed
                GROUP BY series_key, bucket_tick
                ORDER BY series_key, bucket_tick
                """;
        var spec = jdbc.sql(sql).param("runId", runId).param("keys", seriesKeys).param("from", fromTick)
                .param("to", toTick);
        if (bucketSize > 1) {
            spec = spec.param("bucket", (long) bucketSize);
        }
        return groupByKey(spec.query((ResultSet rs, int rowNum) -> new KeyedPoint(rs.getString("series_key"),
                rs.getLong("bucket_tick"), rs.getDouble("value"))).list());
    }

    /**
     * Reads pre-aggregated buckets, re-aggregating when the request wants a coarser grid.
     *
     * <p>The re-aggregation weights each bucket by how many samples went into it, so a coarse point
     * is the mean of the underlying samples rather than a mean of means - those differ whenever a
     * bucket is short, which the last bucket of a run usually is.
     */
    private Map<String, List<Point>> fetchFromRollups(UUID runId, List<String> seriesKeys, long fromTick,
            long toTick, int rollupBucketTicks, int requestedBucket) {
        List<KeyedPoint> rows = jdbc.sql("""
                SELECT series_key, bucket_tick,
                       sum(avg_value * sample_count) / nullif(sum(sample_count), 0) AS value
                FROM (
                    SELECT d.series_key, (r.bucket_tick / :requested) * :requested AS bucket_tick,
                           r.avg_value, r.sample_count
                    FROM metric_rollup r JOIN metric_series d ON d.id = r.series_id
                    WHERE r.run_id = :runId AND d.series_key IN (:keys) AND r.bucket_size = :bucket
                      AND r.bucket_tick BETWEEN :from AND :to
                ) regrouped
                GROUP BY series_key, bucket_tick
                ORDER BY series_key, bucket_tick
                """).param("runId", runId).param("keys", seriesKeys).param("bucket", rollupBucketTicks)
                .param("requested", (long) requestedBucket).param("from", fromTick).param("to", toTick)
                .query((ResultSet rs, int rowNum) -> new KeyedPoint(rs.getString("series_key"),
                        rs.getLong("bucket_tick"), rs.getDouble("value")))
                .list();
        return groupByKey(rows);
    }

    private static Map<String, List<Point>> groupByKey(List<KeyedPoint> rows) {
        Map<String, List<Point>> byKey = new LinkedHashMap<>();
        for (KeyedPoint row : rows) {
            byKey.computeIfAbsent(row.seriesKey(), key -> new ArrayList<>())
                    .add(new Point(row.tick(), row.value()));
        }
        return byKey;
    }

    private record KeyedPoint(String seriesKey, long tick, double value) {
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

    /**
     * Drops everything recorded after a tick.
     *
     * <p>Used when a run is rewound in place. The samples after the restore point describe a future
     * the run no longer has, and leaving them would draw a chart that contradicts the run's own
     * state - the tick counter says 200 while the line runs to 600.
     */
    public void deleteSamplesAfter(UUID runId, long tick) {
        jdbc.sql("DELETE FROM metric_sample WHERE run_id = :runId AND tick > :tick").param("runId", runId)
                .param("tick", tick).update();
        jdbc.sql("DELETE FROM metric_rollup WHERE run_id = :runId AND bucket_tick >= :tick").param("runId", runId)
                .param("tick", tick).update();
    }

    /**
     * Compacts a run's raw samples into rollups, keeping only recent detail.
     *
     * <p>Samples are the bulk of the database and a long run's early history is never read at full
     * resolution. Rolling it up and deleting the raw rows keeps the storage cost of a run bounded
     * by how long it ran rather than by how long it is kept.
     *
     * @return the number of raw sample rows removed
     */
    public int compactBefore(UUID runId, long tick, int bucketSize) {
        buildRollups(runId, bucketSize, 0, tick - 1);
        return jdbc.sql("DELETE FROM metric_sample WHERE run_id = :runId AND tick < :tick").param("runId", runId)
                .param("tick", tick).update();
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
