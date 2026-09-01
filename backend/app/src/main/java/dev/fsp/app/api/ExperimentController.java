package dev.fsp.app.api;

import dev.fsp.app.api.ApiDtos.RunSummaryView;
import dev.fsp.app.persistence.MetricRepository;
import dev.fsp.app.persistence.RunRecords.Run;
import dev.fsp.app.persistence.SeriesRecords.Point;
import dev.fsp.app.persistence.SeriesRecords.Series;
import dev.fsp.app.run.MetricsProperties;
import dev.fsp.app.run.RunCommand;
import dev.fsp.app.run.RunService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Running the same model many times over.
 *
 * <p>A single run of a stochastic model shows one thing that could have happened, not what the
 * model does. Answering "does this configuration drift into resentment" needs a spread of seeds,
 * which until now meant starting runs one at a time by hand and eyeballing them.
 *
 * <p>An experiment is deliberately not a new stored entity. It is a set of ordinary runs launched
 * together, so every existing surface - charts, log, memories, forking, deletion - keeps working on
 * each of them, and the summary is computed on demand from the same series everything else reads.
 */
@RestController
@RequestMapping("/api/v1/experiments")
public class ExperimentController {

    /** Enough replicates to see a distribution, few enough not to swamp a laptop. */
    private static final int MAX_REPLICATES = 50;

    private final RunService runs;
    private final MetricRepository metrics;
    private final MetricsProperties metricsProperties;

    public ExperimentController(RunService runs, MetricRepository metrics, MetricsProperties metricsProperties) {
        this.runs = runs;
        this.metrics = metrics;
        this.metricsProperties = metricsProperties;
    }

    /**
     * @param replicates  how many runs to start
     * @param seed        base seed; each replicate derives its own from it so the whole experiment
     *                    is repeatable, not just each run within it
     * @param ticks       how far to run each replicate, or 0 to leave them paused at the start
     */
    public record StartExperiment(String systemVersionId, String name, int replicates, String seed, Double speed,
            long ticks) {
    }

    public record ExperimentView(String name, List<RunSummaryView> runs) {
    }

    /** Starts a set of replicates and returns them; they run in the background like any other run. */
    @PostMapping
    public ExperimentView start(@RequestBody StartExperiment request) {
        int replicates = Math.clamp(request.replicates() <= 0 ? 10 : request.replicates(), 1, MAX_REPLICATES);
        UUID versionId = UUID.fromString(request.systemVersionId());
        long baseSeed = parseSeed(request.seed());
        String name = request.name() == null || request.name().isBlank() ? "experiment" : request.name();

        // Replicate seeds are drawn from a generator seeded by the base rather than being
        // base + i: consecutive seeds are not independent in any generator worth using, and an
        // experiment whose replicates correlate is worse than useless because it looks fine.
        Random seedSource = new Random(baseSeed);

        List<RunSummaryView> started = new ArrayList<>(replicates);
        for (int i = 0; i < replicates; i++) {
            Run run = runs.create(versionId, name + " #" + (i + 1), seedSource.nextLong(), request.speed(), false);
            if (request.ticks() > 0) {
                runs.control(run.id(), new RunCommand.Step(Math.toIntExact(Math.min(request.ticks(), Integer.MAX_VALUE))));
            }
            started.add(RunViews.toSummary(run, runs.liveSnapshot(run.id()), 0));
        }
        return new ExperimentView(name, started);
    }

    /** One series summarised across several runs: mean, spread and range at each tick. */
    public record Band(long tick, double mean, double min, double max, double stdDev, int samples) {
    }

    public record ComparisonView(String seriesKey, int resolution, List<Band> bands, List<String> runIds) {
    }

    /**
     * Aggregates one series across runs.
     *
     * <p>Returns a band rather than a mean alone. The interesting result of a sweep is usually how
     * wide the outcomes are, and a mean line hides exactly that: two configurations with the same
     * average can differ entirely in whether any individual run ends up anywhere near it.
     */
    @GetMapping("/compare")
    public ComparisonView compare(@RequestParam List<String> runIds, @RequestParam String key,
            @RequestParam(defaultValue = "0") long from, @RequestParam(defaultValue = "-1") long to,
            @RequestParam(defaultValue = "0") int resolution) {
        List<UUID> ids = runIds.stream().map(UUID::fromString).toList();
        long upper = to;
        if (upper < 0) {
            upper = ids.stream().mapToLong(metrics::latestSampledTick).max().orElse(0L);
        }
        int bucket = metricsProperties.rollupEnabled() ? metricsProperties.rollupBucketTicks() : 0;

        // Aligning on tick: replicates advance at their own pace, and bucketing can leave them with
        // points at different ticks, so the band is built from whatever each run actually has there.
        Map<Long, List<Double>> byTick = new java.util.TreeMap<>();
        int usedResolution = 1;
        for (UUID id : ids) {
            Series series = metrics.fetchSeries(id, List.of(key), from, upper, resolution, bucket)
                    .get(key);
            if (series == null) {
                continue;
            }
            usedResolution = Math.max(usedResolution, series.resolution());
            for (Point point : series.points()) {
                byTick.computeIfAbsent(point.tick(), tick -> new ArrayList<>()).add(point.value());
            }
        }

        List<Band> bands = new ArrayList<>(byTick.size());
        for (Map.Entry<Long, List<Double>> entry : byTick.entrySet()) {
            List<Double> values = entry.getValue();
            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            double variance = values.stream().mapToDouble(value -> (value - mean) * (value - mean)).sum()
                    / Math.max(1, values.size());
            bands.add(new Band(entry.getKey(), mean, min, max, Math.sqrt(variance), values.size()));
        }
        return new ComparisonView(key, usedResolution, bands, runIds);
    }

    private static long parseSeed(String seed) {
        if (seed == null || seed.isBlank()) {
            return java.util.concurrent.ThreadLocalRandom.current().nextLong();
        }
        try {
            return Long.parseLong(seed.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("seed must be a whole number: " + seed);
        }
    }
}
