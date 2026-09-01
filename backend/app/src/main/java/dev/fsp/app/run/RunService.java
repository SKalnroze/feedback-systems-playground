package dev.fsp.app.run;

import dev.fsp.app.modules.ModuleRegistry;
import dev.fsp.app.persistence.MetricRepository;
import dev.fsp.app.persistence.RunLogRepository;
import dev.fsp.app.persistence.RunRecords.Checkpoint;
import dev.fsp.app.persistence.RunRecords.Run;
import dev.fsp.app.persistence.RunRepository;
import dev.fsp.app.persistence.RunStatus;
import dev.fsp.app.persistence.SystemRecords.SystemVersion;
import dev.fsp.app.persistence.SystemRepository;
import dev.fsp.engine.Engine;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.state.SimulationState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Everything the API can ask of a run: create it, drive it, checkpoint it, fork it.
 *
 * <p>Sits between the HTTP layer and {@link RunEngineHost}, and is the only place that knows how a
 * stored run becomes a live one. Loading is lazy: a run that nobody is looking at costs nothing
 * until someone asks it to do something.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final SystemRepository systems;
    private final RunRepository runs;
    private final RunLogRepository logs;
    private final MetricRepository metrics;
    private final RunEngineHost host;
    private final SnapshotCodec codec;
    private final ModuleRegistry modules;

    public RunService(SystemRepository systems, RunRepository runs, RunLogRepository logs, MetricRepository metrics,
            RunEngineHost host, SnapshotCodec codec, ModuleRegistry modules) {
        this.systems = systems;
        this.runs = runs;
        this.logs = logs;
        this.metrics = metrics;
        this.host = host;
        this.codec = codec;
        this.modules = modules;
    }

    public List<Run> findAll(int limit, int offset) {
        return runs.findAll(limit, offset);
    }

    public long countAll() {
        return runs.countAll();
    }

    public Optional<Run> find(UUID runId) {
        return runs.find(runId);
    }

    public List<Run> findChildren(UUID runId) {
        return runs.findChildren(runId);
    }

    /** Creates a run against a published system version, optionally starting it straight away. */
    public Run create(UUID systemVersionId, String name, Long seed, Double speed, boolean autoStart) {
        return create(systemVersionId, name, "", seed, speed, autoStart);
    }

    public Run create(UUID systemVersionId, String name, String description, Long seed, Double speed,
            boolean autoStart) {
        SystemVersion version = requireVersion(systemVersionId);
        List<SystemSpec.ValidationIssue> issues = version.spec().validate().stream()
                .filter(SystemSpec.ValidationIssue::isError).toList();
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("system version has validation errors: " + issues);
        }

        long effectiveSeed = seed == null ? java.util.concurrent.ThreadLocalRandom.current().nextLong() : seed;
        double effectiveSpeed = speed == null ? 10.0 : speed;
        UUID runId = runs.create(systemVersionId, name == null || name.isBlank() ? version.spec().name() : name,
                description, effectiveSeed, effectiveSpeed, null, null, 0L);

        Engine engine = newEngine(version.spec());
        SimulationState state = engine.createInitialState(effectiveSeed);
        RunSession session = host.host(runId, engine, state, RunStatus.CREATED, effectiveSpeed);
        logs.appendOne(runId, 0L, "CONTROL", null, "run created from version " + version.version());

        if (autoStart) {
            session.submit(new RunCommand.Start());
        }
        return runs.find(runId).orElseThrow();
    }

    /** Sends a control command, loading the run first if it is not resident. */
    public void control(UUID runId, RunCommand command) {
        RunSession session = ensureHosted(runId);
        session.submit(command);
    }

    /** Writes a checkpoint immediately and returns its id. */
    public UUID checkpoint(UUID runId, String label) {
        RunSession session = ensureHosted(runId);
        if (session.status() == RunStatus.RUNNING) {
            // Let the loop write it at a tick boundary so the snapshot is never half a tick old.
            session.submit(new RunCommand.Checkpoint(label));
            return null;
        }
        return session.writeCheckpoint(label, false);
    }

    public List<Checkpoint> checkpoints(UUID runId) {
        return runs.findCheckpoints(runId);
    }

    /**
     * Restores a checkpoint.
     *
     * <p>Forking creates a separate run sharing the same history up to that tick, which is how the
     * tool answers "what if the argument had not happened": run on, fork back, change one event,
     * and compare the two on the same chart. Restoring in place instead rewinds the run itself and
     * discards the samples after that tick, since they no longer describe it.
     */
    public Run restore(UUID checkpointId, boolean fork, String name) {
        Checkpoint checkpoint = runs.findCheckpoint(checkpointId)
                .orElseThrow(() -> new IllegalArgumentException("no such checkpoint: " + checkpointId));
        Run source = runs.find(checkpoint.runId()).orElseThrow();
        SystemVersion version = requireVersion(source.systemVersionId());
        EngineSnapshot snapshot = codec.decode(runs.loadCheckpointState(checkpointId)
                .orElseThrow(() -> new IllegalStateException("checkpoint has no stored state")));

        Engine engine = newEngine(version.spec());
        SimulationState state = snapshot.restore();
        engine.graph().primeRateBaseline(state);

        if (fork) {
            UUID forkId = runs.create(source.systemVersionId(),
                    name == null || name.isBlank() ? source.name() + " (fork @" + checkpoint.tick() + ")" : name,
                    source.seed(), source.speedTicksPerSecond(), source.id(), checkpoint.tick(), checkpoint.tick());
            RunSession forked = host.host(forkId, engine, state, RunStatus.PAUSED,
                    source.speedTicksPerSecond());
            // The fork gets its own copy of the checkpoint straight away. Checkpoints belong to the
            // run that wrote them, so without this a fork has none of its own, and the moment it is
            // evicted and reloaded it would find nothing to resume from and silently restart at
            // tick zero - losing exactly the history that made forking worth doing.
            forked.writeCheckpoint("forked from " + source.name() + " at tick " + checkpoint.tick(), false);
            runs.updateProgress(forkId, checkpoint.tick(), RunStatus.PAUSED);
            logs.appendOne(forkId, checkpoint.tick(), "CONTROL", null,
                    "forked from run " + source.id() + " at tick " + checkpoint.tick());
            return runs.find(forkId).orElseThrow();
        }

        host.evict(source.id());
        host.host(source.id(), engine, state, RunStatus.PAUSED, source.speedTicksPerSecond());
        // Everything after the restore point belongs to a future this run no longer has. Keeping it
        // would leave the chart drawing past the tick the run is actually at.
        metrics.deleteSamplesAfter(source.id(), checkpoint.tick());
        logs.deleteAfter(source.id(), checkpoint.tick());
        runs.updateProgress(source.id(), checkpoint.tick(), RunStatus.PAUSED);
        logs.appendOne(source.id(), checkpoint.tick(), "CONTROL", null,
                "restored to checkpoint at tick " + checkpoint.tick());
        return runs.find(source.id()).orElseThrow();
    }

    public void describe(UUID runId, String description) {
        runs.describe(runId, description);
    }

    public void describeCheckpoint(UUID checkpointId, String description) {
        runs.describeCheckpoint(checkpointId, description);
    }

    public void rename(UUID runId, String name) {
        runs.rename(runId, name);
    }

    public void delete(UUID runId) {
        host.evict(runId);
        runs.delete(runId);
    }

    public Optional<RunSnapshot> liveSnapshot(UUID runId) {
        return host.session(runId).map(RunSession::snapshot);
    }

    /** The spec a run is executing, for the UI's series picker and graph overlay. */
    public Optional<SystemSpec> specOf(UUID runId) {
        return runs.find(runId).flatMap(run -> systems.findVersion(run.systemVersionId()))
                .map(SystemVersion::spec);
    }

    /**
     * Loads a run into memory if it is not already there, resuming from its latest checkpoint.
     *
     * <p>Ticks recorded after that checkpoint are replayed rather than trusted: the samples in the
     * database describe them, but the engine state that produced them was lost, and continuing from
     * a stale state would silently break the run's determinism.
     */
    public RunSession ensureHosted(UUID runId) {
        Optional<RunSession> existing = host.session(runId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Run run = runs.find(runId).orElseThrow(() -> new IllegalArgumentException("no such run: " + runId));
        SystemVersion version = requireVersion(run.systemVersionId());
        Engine engine = newEngine(version.spec());

        Optional<Checkpoint> latest = runs.findLatestCheckpoint(runId);
        SimulationState state;
        if (latest.isPresent()) {
            state = codec.decode(runs.loadCheckpointState(latest.get().id()).orElseThrow()).restore();
            engine.graph().primeRateBaseline(state);
            log.info("resumed run {} from checkpoint at tick {}", runId, state.tick());
        } else {
            state = engine.createInitialState(run.seed());
            if (run.currentTick() > 0) {
                // Nothing to resume from, but the run had got somewhere. Say so plainly rather than
                // quietly rewinding it: the recorded history no longer describes the live state.
                log.warn("run {} reached tick {} but has no checkpoint; restarting it from tick 0", runId,
                        run.currentTick());
                logs.appendOne(runId, 0L, "NOTE", null,
                        "restarted from tick 0: no checkpoint was available to resume from");
            } else {
                log.info("no checkpoint for run {}; starting from tick 0", runId);
            }
        }

        RunStatus status = run.status().isTerminal() ? run.status() : RunStatus.PAUSED;
        RunSession session = host.host(runId, engine, state, status, run.speedTicksPerSecond());
        // Anything recorded past where the run actually resumed describes a history it no longer
        // has, and left alone it makes the run contradict itself: a tick counter reading 250 under
        // a chart that runs to 300. The comparison is against the samples rather than against the
        // recorded tick, because a run damaged by an earlier resume has already had its tick moved
        // back to agree with the checkpoint while its samples were left where they were.
        long sampledThrough = metrics.latestSampledTick(runId);
        if (!run.status().isTerminal() && sampledThrough > state.tick()) {
            log.warn("run {} resumed at tick {} but had samples through {}; discarding the difference", runId,
                    state.tick(), sampledThrough);
            metrics.deleteSamplesAfter(runId, state.tick());
            logs.deleteAfter(runId, state.tick());
            logs.appendOne(runId, state.tick(), "NOTE", null, "resumed from tick " + state.tick()
                    + "; history after that point was discarded because no later checkpoint existed");
        }
        if (state.tick() != run.currentTick()) {
            runs.updateProgress(runId, state.tick(), status);
        }
        return session;
    }

    /** Deletes a run's samples, e.g. before re-running it from the start. */
    public void clearSeries(UUID runId) {
        metrics.deleteSamples(runId);
    }

    private Engine newEngine(SystemSpec spec) {
        return new Engine(spec, modules.allRules());
    }

    private SystemVersion requireVersion(UUID versionId) {
        return systems.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("no such system version: " + versionId));
    }
}
