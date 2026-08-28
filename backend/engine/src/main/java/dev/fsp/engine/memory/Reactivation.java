package dev.fsp.engine.memory;

/**
 * A single retrieval of a memory: something cued it and the trace was strengthened.
 *
 * @param tick          when the reactivation happened
 * @param boost         added to the memory's effective initial strength (consolidation)
 * @param stabilityGain added to the memory's stability, slowing subsequent decay (spacing effect)
 * @param resetClock    whether the decay clock restarted at this tick
 * @param triggerId     which trigger fired, for the audit log
 */
public record Reactivation(long tick, double boost, double stabilityGain, boolean resetClock, String triggerId) {

    public Reactivation {
        if (boost < 0.0) {
            throw new IllegalArgumentException("boost must not be negative: " + boost);
        }
        if (stabilityGain < 0.0) {
            throw new IllegalArgumentException("stabilityGain must not be negative: " + stabilityGain);
        }
    }

    public static Reactivation at(long tick) {
        return new Reactivation(tick, 0.0, 0.0, false, null);
    }
}
