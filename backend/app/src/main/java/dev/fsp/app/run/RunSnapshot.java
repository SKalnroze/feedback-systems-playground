package dev.fsp.app.run;

import dev.fsp.app.persistence.RunStatus;
import java.util.Map;
import java.util.UUID;

/**
 * What a live run looks like from outside: enough to render the control bar and the latest chart
 * point without querying the database.
 *
 * @param latestValues most recent value of each sampled series
 */
public record RunSnapshot(UUID runId, RunStatus status, long tick, double speedTicksPerSecond, int memoryCount,
        Map<String, Double> latestValues, long ticksPerSecondActual) {
}
