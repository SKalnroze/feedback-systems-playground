package dev.fsp.engine.event;

import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;

/**
 * The random streams available to one event generator.
 *
 * <p>Two are needed, and conflating them is a real bug: a per-tick stream for "did it happen this
 * tick", and a schedule stream keyed by something other than the tick, for decisions a generator
 * must make consistently while being asked about several different ticks. A jittered weekly event
 * has to give the same answer for "which tick does week 3 land on" no matter which tick is asking.
 *
 * @param runSeed  the run's master seed
 * @param eventKey stable hash of the event definition's id, so events do not share draws
 */
public record EventRandom(long runSeed, long eventKey) {

    public static EventRandom forEvent(long runSeed, String eventId) {
        return new EventRandom(runSeed, dev.fsp.engine.rng.SplitMix64.seed(eventId.hashCode()));
    }

    /** Stream for decisions belonging to this tick alone. */
    public Rng tickStream(long tick) {
        return Rng.of(runSeed, tick, RngStream.EVENT_GENERATION, eventKey);
    }

    /** Stream keyed by a schedule slot, giving the same answer whichever tick asks. */
    public Rng scheduleStream(long slot) {
        return Rng.of(runSeed, slot, RngStream.EVENT_SCHEDULE, eventKey);
    }
}
