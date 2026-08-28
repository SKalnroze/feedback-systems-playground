package dev.fsp.engine.spec;

/**
 * Run-level knobs that are not part of the model itself.
 *
 * @param metricSampleInterval    ticks between metric samples; 1 records everything, higher values
 *                                trade resolution for storage on long runs
 * @param autoCheckpointInterval  ticks between automatic checkpoints, 0 to disable
 * @param maxTicks                stop the run at this tick, 0 for open-ended
 * @param interactionsPerTick     how many interaction slots each tick offers to the rule set
 * @param sampleMemoryStrength    whether individual memory strengths are recorded as series, which
 *                                is illuminating but multiplies the data volume
 */
public record SimulationSettings(int metricSampleInterval, int autoCheckpointInterval, long maxTicks,
        int interactionsPerTick, boolean sampleMemoryStrength) {

    public static final SimulationSettings DEFAULT = new SimulationSettings(1, 500, 0L, 1, false);

    public SimulationSettings {
        if (metricSampleInterval <= 0) {
            throw new IllegalArgumentException("metricSampleInterval must be positive: " + metricSampleInterval);
        }
        if (autoCheckpointInterval < 0) {
            throw new IllegalArgumentException("autoCheckpointInterval must not be negative");
        }
        if (maxTicks < 0L) {
            throw new IllegalArgumentException("maxTicks must not be negative");
        }
        if (interactionsPerTick < 0) {
            throw new IllegalArgumentException("interactionsPerTick must not be negative");
        }
    }

    public boolean hasTickLimit() {
        return maxTicks > 0L;
    }

    public boolean autoCheckpointsEnabled() {
        return autoCheckpointInterval > 0;
    }

    public boolean shouldSampleAt(long tick) {
        return tick % metricSampleInterval == 0;
    }
}
