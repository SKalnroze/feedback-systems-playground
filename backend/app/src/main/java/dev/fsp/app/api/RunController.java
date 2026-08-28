package dev.fsp.app.api;

import dev.fsp.app.api.ApiDtos.CheckpointRequest;
import dev.fsp.app.api.ApiDtos.CheckpointView;
import dev.fsp.app.api.ApiDtos.ControlRequest;
import dev.fsp.app.api.ApiDtos.CreateRunRequest;
import dev.fsp.app.api.ApiDtos.RenameRequest;
import dev.fsp.app.api.ApiDtos.RestoreRequest;
import dev.fsp.app.api.ApiDtos.RunDetailView;
import dev.fsp.app.api.ApiDtos.RunSummaryView;
import dev.fsp.app.api.ApiDtos.SpeedRequest;
import dev.fsp.app.persistence.MetricRepository;
import dev.fsp.app.persistence.RunLogRepository;
import dev.fsp.app.persistence.RunRecords.Checkpoint;
import dev.fsp.app.persistence.RunRecords.Run;
import dev.fsp.app.persistence.SystemRepository;
import dev.fsp.app.run.RunBroadcaster;
import dev.fsp.app.run.RunCommand;
import dev.fsp.app.run.RunService;
import dev.fsp.app.run.RunSnapshot;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Creating and driving runs. */
@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunService runs;
    private final SystemRepository systems;
    private final MetricRepository metrics;
    private final RunLogRepository logs;
    private final RunBroadcaster broadcaster;

    public RunController(RunService runs, SystemRepository systems, MetricRepository metrics, RunLogRepository logs,
            RunBroadcaster broadcaster) {
        this.runs = runs;
        this.systems = systems;
        this.metrics = metrics;
        this.logs = logs;
        this.broadcaster = broadcaster;
    }

    @GetMapping
    public List<RunSummaryView> list() {
        return runs.findAll().stream().map(this::toSummary).toList();
    }

    @GetMapping("/{id}")
    public RunDetailView get(@PathVariable UUID id) {
        Run run = runs.find(id).orElseThrow(() -> new NotFoundException("run", id));
        RunSnapshot live = runs.liveSnapshot(id).orElse(null);
        return new RunDetailView(toSummary(run), runs.specOf(id).orElse(null), metrics.countSamples(id),
                logs.countEntries(id), live == null ? 0 : live.ticksPerSecondActual(),
                live == null ? Map.of() : live.latestValues());
    }

    /** Runs forked from this one, so the UI can draw the branch tree. */
    @GetMapping("/{id}/forks")
    public List<RunSummaryView> forks(@PathVariable UUID id) {
        return runs.findChildren(id).stream().map(this::toSummary).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RunSummaryView create(@Valid @RequestBody CreateRunRequest request) {
        UUID versionId = UUID.fromString(request.systemVersionId());
        return toSummary(
                runs.create(versionId, request.name(), request.parsedSeed(), request.speed(), request.autoStart()));
    }

    /**
     * Play, pause, step and stop.
     *
     * <p>One endpoint rather than four, because they are all the same thing from the run's point of
     * view: a command queued for the next tick boundary.
     */
    @PostMapping("/{id}/control")
    public RunSummaryView control(@PathVariable UUID id, @Valid @RequestBody ControlRequest request) {
        RunCommand command = switch (request.action().toUpperCase(Locale.ROOT)) {
            case "START" -> new RunCommand.Start();
            case "PAUSE" -> new RunCommand.Pause();
            case "RESUME" -> new RunCommand.Resume();
            case "STOP" -> new RunCommand.Stop();
            case "STEP" -> new RunCommand.Step(request.ticks() == null ? 1 : request.ticks());
            default -> throw new IllegalArgumentException("unknown action: " + request.action());
        };
        runs.control(id, command);
        return toSummary(runs.find(id).orElseThrow(() -> new NotFoundException("run", id)));
    }

    /** Changes pace without interrupting the run. Zero or less means as fast as possible. */
    @PatchMapping("/{id}/speed")
    public RunSummaryView speed(@PathVariable UUID id, @RequestBody SpeedRequest request) {
        runs.control(id, new RunCommand.SetSpeed(request.ticksPerSecond()));
        return toSummary(runs.find(id).orElseThrow(() -> new NotFoundException("run", id)));
    }

    @PatchMapping("/{id}/name")
    public RunSummaryView rename(@PathVariable UUID id, @Valid @RequestBody RenameRequest request) {
        runs.rename(id, request.name());
        return toSummary(runs.find(id).orElseThrow(() -> new NotFoundException("run", id)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        runs.delete(id);
    }

    // --- checkpoints ---------------------------------------------------------------------------

    @GetMapping("/{id}/checkpoints")
    public List<CheckpointView> checkpoints(@PathVariable UUID id) {
        return runs.checkpoints(id).stream().map(RunController::toCheckpointView).toList();
    }

    /**
     * Writes a checkpoint. A running simulation writes it at its next tick boundary, so the
     * response may arrive before the checkpoint exists; the client learns about it from the run's
     * checkpoint list or its event stream.
     */
    @PostMapping("/{id}/checkpoints")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public List<CheckpointView> checkpoint(@PathVariable UUID id, @RequestBody CheckpointRequest request) {
        runs.checkpoint(id, request.label());
        return runs.checkpoints(id).stream().map(RunController::toCheckpointView).toList();
    }

    /** Rewinds this run to a checkpoint, or branches a new run from it. */
    @PostMapping("/{id}/restore")
    public RunSummaryView restore(@PathVariable UUID id, @Valid @RequestBody RestoreRequest request) {
        Run restored = runs.restore(UUID.fromString(request.checkpointId()), request.fork(), request.name());
        return toSummary(restored);
    }

    private RunSummaryView toSummary(Run run) {
        String systemName = systems.findVersion(run.systemVersionId()).map(version -> version.spec().name())
                .orElse("(unknown system)");
        // Prefer the live tick: the stored one lags by up to one flush interval.
        long tick = runs.liveSnapshot(run.id()).map(RunSnapshot::tick).orElse(run.currentTick());
        var status = runs.liveSnapshot(run.id()).map(RunSnapshot::status).orElse(run.status());
        return new RunSummaryView(run.id().toString(), run.name(), status.name(), tick, run.speedTicksPerSecond(),
                Long.toString(run.seed()), systemName,
                run.parentRunId() == null ? null : run.parentRunId().toString(),
                run.forkedFromTick(), broadcaster.subscriberCount(run.id()), run.errorMessage());
    }

    private static CheckpointView toCheckpointView(Checkpoint checkpoint) {
        return new CheckpointView(checkpoint.id().toString(), checkpoint.tick(), checkpoint.label(),
                checkpoint.stateBytes(), checkpoint.automatic(), checkpoint.createdAt().toString());
    }
}
