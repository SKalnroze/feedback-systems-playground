package dev.fsp.app.api;

import dev.fsp.app.api.ApiDtos.RunSummaryView;
import dev.fsp.app.persistence.RunRecords.Run;
import dev.fsp.app.run.RunSnapshot;
import java.util.Optional;

/**
 * Turning a stored run into the shape the API sends.
 *
 * <p>Shared rather than private to one controller: several endpoints return a run summary, and
 * having each build its own risks them disagreeing about something as visible as which tick to
 * show.
 */
final class RunViews {

    private RunViews() {
    }

    /**
     * @param live     the resident session's snapshot, when the run has one
     * @param watchers how many clients are subscribed to its live stream
     */
    static RunSummaryView toSummary(Run run, Optional<RunSnapshot> live, int watchers) {
        // Prefer the live tick and status: the stored ones lag by up to one flush interval, which
        // at speed is thousands of ticks.
        long tick = live.map(RunSnapshot::tick).orElse(run.currentTick());
        var status = live.map(RunSnapshot::status).orElse(run.status());
        return new RunSummaryView(run.id().toString(), run.name(),
                run.description() == null ? "" : run.description(), status.name(), tick, run.speedTicksPerSecond(),
                Long.toString(run.seed()), run.systemName() == null ? "(unknown system)" : run.systemName(),
                run.parentRunId() == null ? null : run.parentRunId().toString(), run.forkedFromTick(), watchers,
                run.errorMessage());
    }
}
