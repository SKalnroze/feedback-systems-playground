package dev.fsp.engine.memory;

import java.util.List;

/**
 * The decay-relevant view of a memory. Decay models see only this, never the full
 * {@link MemoryRecord}, which keeps the maths independently testable and stops model
 * implementations from reaching into simulation state.
 *
 * <p>A long-lived memory can be rehearsed thousands of times, and its strength is recomputed on
 * every read, so the history is exposed two ways: running totals that are cheap to consult, and a
 * bounded window of the most recent reactivations for the models that need individual timings.
 * {@link #hasCompleteHistory()} says whether that window is the whole story.
 */
public interface Trace {

    /** Strength at the moment the memory was laid down, in {@code (0, 1]}. */
    double initialStrength();

    /** Tick the memory was first created. */
    long createdTick();

    /**
     * Tick the decay clock currently runs from: the creation tick, or the tick of the most
     * recent reactivation that asked for the clock to be reset.
     */
    long originTick();

    /**
     * The most recent reactivations, oldest first. Bounded: see {@link #hasCompleteHistory()}
     * before treating this as the complete record.
     */
    List<Reactivation> reactivations();

    /**
     * Number of other memories held by the same owner that are similar enough to interfere with
     * retrieval of this one. Zero disables interference regardless of model settings.
     */
    int similarCount();

    /** Total reactivations ever, including any no longer held in the window. */
    default int reactivationCount() {
        return reactivations().size();
    }

    /** True when {@link #reactivations()} holds every reactivation this memory has had. */
    default boolean hasCompleteHistory() {
        return reactivationCount() == reactivations().size();
    }

    /** Sum of every consolidation boost ever earned, including reactivations outside the window. */
    default double totalBoost() {
        double total = 0.0;
        for (Reactivation reactivation : reactivations()) {
            total += reactivation.boost();
        }
        return total;
    }

    /** Sum of every stability gain ever earned, including reactivations outside the window. */
    default double totalStabilityGain() {
        double total = 0.0;
        for (Reactivation reactivation : reactivations()) {
            total += reactivation.stabilityGain();
        }
        return total;
    }

    /** Tick of the oldest reactivation still held in the window, or the creation tick if none. */
    default long oldestRetainedReactivationTick() {
        List<Reactivation> retained = reactivations();
        return retained.isEmpty() ? createdTick() : retained.getFirst().tick();
    }
}
