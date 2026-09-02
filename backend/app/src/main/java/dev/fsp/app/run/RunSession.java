package dev.fsp.app.run;

import dev.fsp.app.persistence.MetricRepository;
import dev.fsp.app.persistence.RunLogRepository;
import dev.fsp.app.persistence.RunRepository;
import dev.fsp.app.persistence.RunStatus;
import dev.fsp.engine.Engine;
import dev.fsp.engine.TickReport;
import dev.fsp.engine.memory.MemoryRecord;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.state.SimulationState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One simulation running in the background.
 *
 * <p>Owns a thread, an engine and its state, and nothing else owns any of them: all outside
 * influence arrives as commands on a queue and is applied between ticks. That single rule is what
 * lets a run be paused, sped up, checkpointed and resumed without ever producing different numbers
 * than an uninterrupted run of the same seed would have produced.
 *
 * <p>Writes to Postgres are batched. A run at a thousand ticks a second would otherwise spend all
 * its time in the database rather than in the simulation, and the UI cannot use per-tick resolution
 * at that pace anyway.
 */
public final class RunSession {

    private static final Logger log = LoggerFactory.getLogger(RunSession.class);

    /** Memory snapshots are heavier than samples, so they are written less often. */
    private static final int MEMORY_SNAPSHOT_EVERY_N_FLUSHES = 8;

    private final UUID runId;
    private final Engine engine;
    private final SystemSpec spec;
    private final SampleCollector collector;
    private final RunRepository runs;
    private final MetricRepository metrics;
    private final RunLogRepository logs;
    private final RunProperties properties;
    private final MetricsProperties metricsProperties;
    private final RunBroadcaster broadcaster;

    private final ConcurrentLinkedQueue<RunCommand> commands = new ConcurrentLinkedQueue<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wakeUp = lock.newCondition();

    private final Map<String, Long> seriesIds = new HashMap<>();
    private final List<MetricRepository.SampleRow> pendingSamples = new ArrayList<>();
    private final List<RunLogRepository.LogRow> pendingLogs = new ArrayList<>();
    private Map<String, Double> latestValues = Map.of();

    private volatile SimulationState state;
    private volatile RunStatus status;
    private volatile double speedTicksPerSecond;
    private volatile boolean shuttingDown;
    private volatile long observedTicksPerSecond;

    private long remainingSteps;
    private String pendingCheckpointLabel;
    private boolean checkpointRequested;
    private long lastFlushAt = System.currentTimeMillis();
    private long ticksSinceFlush;
    private int flushCount;
    private long lastRateSampleAt = System.nanoTime();
    private long ticksSinceRateSample;
    private long rolledUpThroughTick;
    private boolean rollupsDisabled;

    RunSession(UUID runId, Engine engine, SimulationState state, RunStatus status, double speedTicksPerSecond,
            RunRepository runs, MetricRepository metrics, RunLogRepository logs, RunProperties properties,
            MetricsProperties metricsProperties, RunBroadcaster broadcaster) {
        this.runId = runId;
        this.engine = engine;
        this.spec = engine.spec();
        this.collector = new SampleCollector(spec);
        this.state = state;
        this.status = status;
        this.speedTicksPerSecond = speedTicksPerSecond;
        this.runs = runs;
        this.metrics = metrics;
        this.logs = logs;
        this.properties = properties;
        this.metricsProperties = metricsProperties;
        this.broadcaster = broadcaster;
        this.rolledUpThroughTick = state.tick();
    }

    public UUID runId() {
        return runId;
    }

    public RunStatus status() {
        return status;
    }

    public long tick() {
        return state.tick();
    }

    public SystemSpec spec() {
        return spec;
    }

    /** Live state. Only safe to read from the session's own thread or while it is not running. */
    SimulationState state() {
        return state;
    }

    public RunSnapshot snapshot() {
        return new RunSnapshot(runId, status, state.tick(), speedTicksPerSecond, state.memories().size(),
                latestValues, observedTicksPerSecond);
    }

    /** Queues a command and wakes the loop if it is parked. */
    public void submit(RunCommand command) {
        commands.add(command);
        lock.lock();
        try {
            wakeUp.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Asks the loop to finish and stop, without changing the run's recorded status. */
    void shutdown() {
        shuttingDown = true;
        submit(new RunCommand.Pause());
    }

    // --- the loop ------------------------------------------------------------------------------

    void loop() {
        try {
            while (!shuttingDown && !Thread.currentThread().isInterrupted()) {
                applyCommands();

                if (status != RunStatus.RUNNING) {
                    if (status.isTerminal()) {
                        break;
                    }
                    await();
                    continue;
                }

                long tickStartedAt = System.nanoTime();
                if (!stepOnce()) {
                    break;
                }
                pace(tickStartedAt);
            }
        } catch (RuntimeException e) {
            log.error("run {} failed at tick {}", runId, state.tick(), e);
            flush(true);
            status = RunStatus.FAILED;
            runs.markFailed(runId, e.toString());
            broadcaster.publishStatus(runId, snapshot());
        } finally {
            flush(true);
            if (status == RunStatus.RUNNING) {
                // The process is going down while this run was still going: leave it resumable.
                status = RunStatus.PAUSED;
                runs.updateProgress(runId, state.tick(), RunStatus.PAUSED);
            }
            writeResumeCheckpoint();
        }
    }

    /** Ticks before which a run checkpoints more often than its configured interval. */
    private static final long EARLY_PHASE_TICKS = 250L;

    /** How often to checkpoint inside that early phase. */
    private static final long EARLY_INTERVAL = 25L;

    /**
     * Whether this tick deserves an automatic checkpoint.
     *
     * <p>A run is only resumable from its newest checkpoint, so with an interval of two hundred and
     * fifty a run stopped at tick two hundred has nothing at all to go back to and is lost. That is
     * not a rare case: it is every run interrupted in its first few minutes, which is most of the
     * ones anybody is actively watching.
     *
     * <p>So the early part of a run checkpoints often and then settles into the configured
     * interval. The cost is a handful of extra snapshots at the point when a run is smallest and
     * they are cheapest.
     */
    private boolean shouldAutoCheckpoint() {
        long tick = state.tick();
        long interval = tick <= EARLY_PHASE_TICKS
                ? Math.min(EARLY_INTERVAL, spec.settings().autoCheckpointInterval())
                : spec.settings().autoCheckpointInterval();
        return interval > 0 && tick % interval == 0;
    }

    /** Runs one tick and records what it produced. Returns false when the run has finished. */
    private boolean stepOnce() {
        TickReport report = engine.tick(state);
        recordTick(report);

        if (remainingSteps > 0 && --remainingSteps == 0) {
            transitionTo(RunStatus.PAUSED);
        }
        if (checkpointRequested) {
            writeCheckpoint(pendingCheckpointLabel, false);
            checkpointRequested = false;
            pendingCheckpointLabel = null;
        }
        if (spec.settings().autoCheckpointsEnabled() && shouldAutoCheckpoint()) {
            writeCheckpoint(null, true);
        }
        if (spec.settings().hasTickLimit() && state.tick() >= spec.settings().maxTicks()) {
            transitionTo(RunStatus.COMPLETED);
            flush(true);
            return false;
        }

        ticksSinceFlush++;
        if (shouldFlush()) {
            flush(false);
        }
        return true;
    }

    private void recordTick(TickReport report) {
        if (spec.settings().shouldSampleAt(report.tick())) {
            Map<String, Double> values = collector.collect(state);
            latestValues = values;
            values.forEach((key, value) -> pendingSamples
                    .add(new MetricRepository.SampleRow(seriesId(key), report.tick(), value)));
        }

        for (TickReport.EventOccurrence occurrence : report.events()) {
            pendingLogs.add(new RunLogRepository.LogRow(report.tick(), "EVENT", occurrence.eventId(),
                    describeEvent(occurrence)));
        }
        for (TickReport.ReactivationRecord reactivation : report.reactivations()) {
            pendingLogs.add(new RunLogRepository.LogRow(report.tick(), "REACTIVATION", reactivation.ownerId(),
                    describeReactivation(reactivation)));
        }
        for (String line : report.logLines()) {
            pendingLogs.add(new RunLogRepository.LogRow(report.tick(), "INTERACTION", null, line));
        }
    }

    private static String describeEvent(TickReport.EventOccurrence occurrence) {
        String targets = occurrence.targetIds().isEmpty() ? "no one" : String.join(", ", occurrence.targetIds());
        return (occurrence.cascaded() ? "cascaded event " : "event ") + occurrence.eventId() + " hit " + targets;
    }

    private static String describeReactivation(TickReport.ReactivationRecord reactivation) {
        return "%s recalled a memory of %s via %s (%.3f to %.3f)".formatted(reactivation.ownerId(),
                reactivation.subjectId(), reactivation.triggerId(), reactivation.strengthBefore(),
                reactivation.strengthAfter());
    }

    // --- commands ------------------------------------------------------------------------------

    private void applyCommands() {
        RunCommand command;
        while ((command = commands.poll()) != null) {
            switch (command) {
                case RunCommand.Start ignored -> transitionTo(RunStatus.RUNNING);
                case RunCommand.Resume ignored -> transitionTo(RunStatus.RUNNING);
                case RunCommand.Pause ignored -> {
                    if (status == RunStatus.RUNNING) {
                        transitionTo(RunStatus.PAUSED);
                    }
                }
                case RunCommand.Stop ignored -> {
                    transitionTo(RunStatus.STOPPED);
                    flush(true);
                }
                case RunCommand.Step step -> {
                    remainingSteps = step.ticks();
                    transitionTo(RunStatus.RUNNING);
                }
                case RunCommand.SetSpeed speed -> {
                    speedTicksPerSecond = Math.min(speed.ticksPerSecond() <= 0 ? Double.MAX_VALUE
                            : speed.ticksPerSecond(), properties.maxTicksPerSecond());
                    runs.updateSpeed(runId, speed.ticksPerSecond());
                }
                case RunCommand.Checkpoint checkpoint -> {
                    checkpointRequested = true;
                    pendingCheckpointLabel = checkpoint.label();
                    // A paused run would otherwise never reach the tick boundary that writes it.
                    if (status != RunStatus.RUNNING) {
                        writeCheckpoint(checkpoint.label(), false);
                        checkpointRequested = false;
                        pendingCheckpointLabel = null;
                    }
                }
            }
        }
    }

    private void transitionTo(RunStatus next) {
        if (status == next) {
            return;
        }
        status = next;
        flush(true);
        runs.updateProgress(runId, state.tick(), next);
        logs.appendOne(runId, state.tick(), "CONTROL", null, "run " + next.name().toLowerCase(java.util.Locale.ROOT));
        broadcaster.publishStatus(runId, snapshot());
    }

    /** Parks until a command arrives, so a paused run costs nothing. */
    private void await() {
        lock.lock();
        try {
            if (commands.isEmpty()) {
                wakeUp.await(1, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    /** Sleeps just enough to hit the requested pace; unthrottled speeds skip this entirely. */
    private void pace(long tickStartedAt) {
        measureRate();
        if (speedTicksPerSecond >= properties.maxTicksPerSecond()) {
            return;
        }
        long targetNanos = (long) (1_000_000_000L / speedTicksPerSecond);
        long elapsed = System.nanoTime() - tickStartedAt;
        long remaining = targetNanos - elapsed;
        if (remaining <= 0) {
            return;
        }
        lock.lock();
        try {
            // Waiting on the condition rather than sleeping: a pause or speed change takes effect
            // immediately instead of after the current tick's delay has run out.
            wakeUp.awaitNanos(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    private void measureRate() {
        ticksSinceRateSample++;
        long elapsed = System.nanoTime() - lastRateSampleAt;
        if (elapsed >= 1_000_000_000L) {
            observedTicksPerSecond = ticksSinceRateSample * 1_000_000_000L / elapsed;
            ticksSinceRateSample = 0;
            lastRateSampleAt = System.nanoTime();
        }
    }

    // --- persistence ---------------------------------------------------------------------------

    private boolean shouldFlush() {
        return ticksSinceFlush >= properties.flushIntervalTicks()
                || System.currentTimeMillis() - lastFlushAt >= properties.flushIntervalMillis();
    }

    private void flush(boolean force) {
        if (pendingSamples.isEmpty() && pendingLogs.isEmpty() && !force) {
            return;
        }
        metrics.insertSamples(runId, pendingSamples);
        logs.append(runId, pendingLogs);
        pendingSamples.clear();
        pendingLogs.clear();
        ticksSinceFlush = 0;
        lastFlushAt = System.currentTimeMillis();

        if (force || ++flushCount % MEMORY_SNAPSHOT_EVERY_N_FLUSHES == 0) {
            writeMemorySnapshot();
        }
        buildRollupsForCompletedBuckets();
        runs.updateProgress(runId, state.tick(), status);
        broadcaster.publishTick(runId, snapshot());
    }

    /**
     * Pre-aggregates buckets that can no longer change.
     *
     * <p>Only whole buckets behind the current tick are built. A bucket the run is still writing
     * into would have to be rebuilt on every flush, and would be wrong in between - and the whole
     * point of a rollup is to answer a query about a long span without touching the raw rows.
     */
    private void buildRollupsForCompletedBuckets() {
        if (!metricsProperties.rollupEnabled() || rollupsDisabled) {
            return;
        }
        int bucket = metricsProperties.rollupBucketTicks();
        long completedThrough = (state.tick() / bucket) * bucket;
        if (completedThrough <= rolledUpThroughTick) {
            return;
        }
        try {
            metrics.buildRollups(runId, bucket, rolledUpThroughTick, completedThrough - 1);
            rolledUpThroughTick = completedThrough;
        } catch (RuntimeException e) {
            // Rollups only make wide chart queries cheaper; the run is the thing that matters.
            // Letting this failure escape would kill the simulation over a lost optimisation, so
            // it is reported once and then left alone for the rest of the run.
            rollupsDisabled = true;
            log.warn("rollups disabled for run {} after a failure at tick {}", runId, state.tick(), e);
            logs.appendOne(runId, state.tick(), "NOTE", null,
                    "metric rollups disabled after an error; charts will read raw samples");
        }
    }

    private void writeMemorySnapshot() {
        List<RunLogRepository.MemoryRow> rows = new ArrayList<>();
        long tick = state.tick();
        state.memories().forEach(memory -> rows.add(toRow(memory, tick)));
        logs.replaceMemories(runId, rows);
    }

    private static RunLogRepository.MemoryRow toRow(MemoryRecord memory, long tick) {
        return new RunLogRepository.MemoryRow(memory.id(), memory.ownerId(), memory.subjectId(), memory.kind(),
                memory.createdTick(), memory.originTick(), memory.initialStrength(), memory.strengthAt(tick),
                memory.valence(), memory.salience(), memory.reactivationCount(), tick);
    }

    /**
     * Writes a checkpoint on the way out, unless one already covers this tick.
     *
     * <p>A run is resumed from its newest checkpoint. Without this, unloading a run that had ticked
     * past its last automatic checkpoint threw away everything since: reopening it silently rewound
     * it to the older tick while its recorded samples still ran to where it had actually got to, so
     * the run and its own history disagreed.
     */
    private void writeResumeCheckpoint() {
        if (state.tick() <= 0) {
            return;
        }
        try {
            if (runs.findLatestCheckpoint(runId).filter(latest -> latest.tick() >= state.tick()).isPresent()) {
                return;
            }
            writeCheckpoint("resume point", true);
        } catch (RuntimeException e) {
            // Losing this costs progress on the next load; failing to shut down costs the process.
            log.warn("could not write a resume checkpoint for run {} at tick {}", runId, state.tick(), e);
        }
    }

    /** Writes a checkpoint of the current state. */
    UUID writeCheckpoint(String label, boolean automatic) {
        byte[] payload = broadcaster.codec().encode(EngineSnapshot.capture(state));
        UUID id = runs.saveCheckpoint(runId, state.tick(), label, payload, automatic);
        logs.appendOne(runId, state.tick(), "CHECKPOINT", label,
                (automatic ? "automatic" : "manual") + " checkpoint (" + payload.length + " bytes)");
        if (automatic) {
            runs.pruneAutomaticCheckpoints(runId, properties.keepAutoCheckpoints());
        }
        return id;
    }

    /** Interns a series key once per run, then reuses the id for every later sample. */
    private long seriesId(String key) {
        Long cached = seriesIds.get(key);
        if (cached != null) {
            return cached;
        }
        long id = metrics.internSeries(runId, key, SampleCollector.objectOf(key), SampleCollector.variableOf(key),
                SampleCollector.categoryOf(key));
        seriesIds.put(key, id);
        return id;
    }

    /** Series keys this run has produced so far, for the picker before any query is made. */
    public Map<String, Double> latestValues() {
        return new LinkedHashMap<>(latestValues);
    }
}
