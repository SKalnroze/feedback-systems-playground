package dev.fsp.app.api;

import dev.fsp.app.api.ApiDtos.LogEntryView;
import dev.fsp.app.api.ApiDtos.MemoryView;
import dev.fsp.app.api.ApiDtos.Page;
import dev.fsp.app.api.ApiDtos.RelationshipView;
import dev.fsp.app.api.ApiDtos.SeriesResponse;
import dev.fsp.app.api.ApiDtos.SeriesView;
import dev.fsp.app.persistence.MetricRepository;
import dev.fsp.app.persistence.RunLogRepository;
import dev.fsp.app.persistence.SeriesRecords.Point;
import dev.fsp.app.persistence.SeriesRecords.Series;
import dev.fsp.app.persistence.SeriesRecords.SeriesDefinition;
import dev.fsp.app.run.MetricsProperties;
import dev.fsp.app.run.RunBroadcaster;
import dev.fsp.app.run.RunService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Looking at what a run did.
 *
 * <p>Four views of the same history, because a single one is never enough: series for how variables
 * moved, the log for why, the memory inspector for what each object is carrying, and the
 * relationship matrix for the shape of the whole group at a glance.
 */
@RestController
@RequestMapping("/api/v1/runs/{runId}")
public class ObservabilityController {

    /** Hard ceiling on rows returned in one request, whatever the caller asks for. */
    private static final int MAX_LIMIT = 5_000;

    private final MetricRepository metrics;
    private final RunLogRepository logs;
    private final RunService runs;
    private final RunBroadcaster broadcaster;
    private final MetricsProperties metricsProperties;

    public ObservabilityController(MetricRepository metrics, RunLogRepository logs, RunService runs,
            RunBroadcaster broadcaster, MetricsProperties metricsProperties) {
        this.metrics = metrics;
        this.logs = logs;
        this.runs = runs;
        this.broadcaster = broadcaster;
        this.metricsProperties = metricsProperties;
    }

    /** Every series this run has produced, for the chart's series picker. */
    @GetMapping("/series")
    public List<SeriesDefinition> seriesCatalogue(@PathVariable UUID runId) {
        return metrics.findSeries(runId);
    }

    /**
     * Values over a tick range.
     *
     * @param keys       series to fetch; several at once, since a chart panel overlays them
     * @param resolution ticks per point, or 0 to let the range decide
     */
    @GetMapping("/series/data")
    public SeriesResponse seriesData(@PathVariable UUID runId, @RequestParam List<String> keys,
            @RequestParam(defaultValue = "0") long from, @RequestParam(defaultValue = "-1") long to,
            @RequestParam(defaultValue = "0") int resolution) {
        long upperBound = to < 0 ? metrics.latestSampledTick(runId) : to;
        Map<String, Series> fetched = metrics.fetchSeries(runId, keys, from, upperBound, resolution,
                metricsProperties.rollupEnabled() ? metricsProperties.rollupBucketTicks() : 0);

        List<SeriesView> views = new ArrayList<>(fetched.size());
        int usedResolution = 1;
        for (Series series : fetched.values()) {
            usedResolution = Math.max(usedResolution, series.resolution());
            views.add(new SeriesView(series.seriesKey(), toPairs(series.points()), series.bucketed()));
        }
        return new SeriesResponse(runId.toString(), from, upperBound, usedResolution, views);
    }

    /**
     * The audit trail: what happened, and when.
     *
     * <p>Newest first and capped, because the interesting question is nearly always "what just
     * happened", and a long run's log is far too large to send whole.
     */
    @GetMapping("/log")
    public Page<LogEntryView> log(@PathVariable UUID runId, @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to, @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "200") int limit) {
        int capped = Math.clamp(limit, 1, MAX_LIMIT);
        List<LogEntryView> entries = logs.find(runId, from, to, type, capped + 1).stream()
                .map(entry -> new LogEntryView(entry.id(), entry.tick(), entry.entryType(), entry.subject(),
                        entry.detail()))
                .toList();
        return Page.of(entries, capped);
    }

    /** What one object currently remembers, strongest first. */
    @GetMapping("/memories")
    public Page<MemoryView> memories(@PathVariable UUID runId, @RequestParam(required = false) String ownerId,
            @RequestParam(defaultValue = "200") int limit) {
        int capped = Math.clamp(limit, 1, MAX_LIMIT);
        List<MemoryView> memories = logs.findMemories(runId, ownerId, capped + 1).stream()
                .map(memory -> new MemoryView(memory.id(), memory.ownerId(), memory.subjectId(), memory.kind(),
                        memory.createdTick(), memory.initialStrength(), memory.currentStrength(), memory.valence(),
                        memory.reactivationCount()))
                .toList();
        return Page.of(memories, capped);
    }

    /** How everyone currently regards everyone else, derived from what they remember. */
    @GetMapping("/relationships")
    public List<RelationshipView> relationships(@PathVariable UUID runId) {
        return logs.relationshipMatrix(runId).stream()
                .map(cell -> new RelationshipView(cell.ownerId(), cell.subjectId(), cell.meanValence(),
                        cell.totalStrength(), cell.memoryCount()))
                .toList();
    }

    /**
     * Live updates while a run is going.
     *
     * <p>Subscribing also loads the run if it was not resident, so opening its page is enough to
     * bring a run recovered from a restart back into memory.
     */
    @GetMapping("/stream")
    public SseEmitter stream(@PathVariable UUID runId) {
        runs.ensureHosted(runId);
        return broadcaster.subscribe(runId);
    }

    /**
     * Points as {@code [tick, value]} pairs rather than objects: a chart with fifty series and two
     * thousand points each is mostly repeated field names otherwise.
     */
    private static List<Number[]> toPairs(List<Point> points) {
        List<Number[]> pairs = new ArrayList<>(points.size());
        for (Point point : points) {
            pairs.add(new Number[] {point.tick(), point.value()});
        }
        return pairs;
    }
}
