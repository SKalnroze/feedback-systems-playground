package dev.fsp.engine.memory;

import dev.fsp.engine.expr.EvalContext;
import dev.fsp.engine.rng.Rng;
import java.util.Map;
import java.util.Set;

/** What a reactivation trigger is allowed to look at when deciding whether a memory comes back. */
public interface TriggerContext {

    long tick();

    /** Ids of event definitions that fired this tick. */
    Set<String> firedEventIds();

    /** Objects involved in anything that happened this tick: events, interactions, observations. */
    Set<String> objectsInPlay();

    /**
     * The current stimulus, as a feature vector. Built from whatever happened this tick, and
     * compared against memory feature vectors for cue-based recall.
     */
    Map<String, Double> cue();

    /** Expression context with the memory's owner bound to SELF and its subject to TARGET. */
    EvalContext evalContextFor(MemoryRecord memory);

    /** Random stream for stochastic triggers, scoped to this tick and memory. */
    Rng rngFor(MemoryRecord memory);
}
