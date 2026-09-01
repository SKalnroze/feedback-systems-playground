package dev.fsp.app.api;

import dev.fsp.app.persistence.MetricRepository;
import dev.fsp.app.persistence.RunLogRepository;
import dev.fsp.app.persistence.RunRecords.LogEntry;
import dev.fsp.app.persistence.SeriesRecords.Point;
import dev.fsp.app.persistence.SeriesRecords.Series;
import dev.fsp.app.run.MetricsProperties;
import dev.fsp.app.run.RunService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Taking a run's data out of the tool.
 *
 * <p>Analysis rarely finishes in the browser. A run that cannot be exported is a run whose findings
 * have to be re-typed somewhere else, so both surfaces the UI reads - the series and the log - can
 * be pulled as CSV.
 *
 * <p>Series export pivots to one column per series against a shared tick column, because that is
 * the shape every spreadsheet and dataframe wants; the long form would make the user pivot it back.
 */
@RestController
@RequestMapping("/api/v1/runs/{runId}/export")
public class ExportController {

    private final MetricRepository metrics;
    private final RunLogRepository logs;
    private final RunService runs;
    private final MetricsProperties metricsProperties;

    public ExportController(MetricRepository metrics, RunLogRepository logs, RunService runs,
            MetricsProperties metricsProperties) {
        this.metrics = metrics;
        this.logs = logs;
        this.runs = runs;
        this.metricsProperties = metricsProperties;
    }

    /** Series as CSV: one row per tick, one column per series. */
    @GetMapping(value = "/series.csv", produces = "text/csv")
    public ResponseEntity<Resource> seriesCsv(@PathVariable UUID runId,
            @RequestParam(required = false) List<String> keys,
            @RequestParam(defaultValue = "0") long from, @RequestParam(defaultValue = "-1") long to,
            @RequestParam(defaultValue = "0") int resolution) {
        List<String> wanted = keys == null || keys.isEmpty()
                ? metrics.findSeries(runId).stream().map(definition -> definition.seriesKey()).toList()
                : keys;
        long upper = to < 0 ? metrics.latestSampledTick(runId) : to;
        Map<String, Series> fetched = metrics.fetchSeries(runId, wanted, from, upper, resolution,
                metricsProperties.rollupEnabled() ? metricsProperties.rollupBucketTicks() : 0);

        // Ticks are collected across every series first: bucketing can leave series with points at
        // slightly different ticks, and a table with ragged rows is not a table.
        TreeSet<Long> ticks = new TreeSet<>();
        for (Series series : fetched.values()) {
            for (Point point : series.points()) {
                ticks.add(point.tick());
            }
        }
        Map<String, Map<Long, Double>> byKey = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Series> entry : fetched.entrySet()) {
            Map<Long, Double> values = new java.util.HashMap<>();
            for (Point point : entry.getValue().points()) {
                values.put(point.tick(), point.value());
            }
            byKey.put(entry.getKey(), values);
        }

        StringBuilder csv = new StringBuilder(ticks.size() * 16);
        csv.append("tick");
        for (String key : wanted) {
            csv.append(',').append(quote(key));
        }
        csv.append('\n');
        for (Long tick : ticks) {
            csv.append(tick);
            for (String key : wanted) {
                Double value = byKey.getOrDefault(key, Map.of()).get(tick);
                csv.append(',');
                if (value != null) {
                    csv.append(value);
                }
            }
            csv.append('\n');
        }
        return attachment(csv.toString(), "run-" + runId + "-series.csv", "text/csv");
    }

    /** The log as CSV, in the order it happened. */
    @GetMapping(value = "/log.csv", produces = "text/csv")
    public ResponseEntity<Resource> logCsv(@PathVariable UUID runId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long from, @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "100000") int limit) {
        List<LogEntry> entries = new ArrayList<>(
                logs.find(runId, from, to, type, Math.clamp(limit, 1, 500_000)));
        // Oldest first for a file, newest first on screen: reading a log top to bottom is reading a
        // story, and the API's default order is built for a page that shows the latest first.
        entries.sort(java.util.Comparator.comparingLong(LogEntry::tick));

        StringBuilder csv = new StringBuilder(entries.size() * 48);
        csv.append("tick,type,subject,detail\n");
        for (LogEntry entry : entries) {
            csv.append(entry.tick()).append(',').append(quote(entry.entryType())).append(',')
                    .append(quote(entry.subject())).append(',').append(quote(entry.detail())).append('\n');
        }
        return attachment(csv.toString(), "run-" + runId + "-log.csv", "text/csv");
    }

    /** The whole run as JSON: spec, series and log together, for archiving or scripting. */
    @GetMapping(value = "/run.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> runJson(@PathVariable UUID runId) {
        var run = runs.find(runId).orElseThrow(() -> new NotFoundException("run", runId));
        return ResponseEntity.ok(Map.of(
                "run", Map.of("id", run.id().toString(), "name", run.name(), "seed", Long.toString(run.seed()),
                        "tick", run.currentTick(), "status", run.status().name(),
                        "systemName", run.systemName() == null ? "" : run.systemName()),
                "spec", runs.specOf(runId).orElse(null),
                "series", metrics.findSeries(runId),
                "checkpoints", runs.checkpoints(runId)));
    }

    private static ResponseEntity<Resource> attachment(String body, String filename, String contentType) {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    /** RFC 4180 quoting: fields carry commas and quotes, and the log's detail carries both. */
    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return '"' + escaped + '"';
    }
}
