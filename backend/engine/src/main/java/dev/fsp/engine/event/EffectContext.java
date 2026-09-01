package dev.fsp.engine.event;

import dev.fsp.engine.expr.EvalContext;
import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.Map;

/**
 * The handle an effect uses to change the world.
 *
 * <p>Effects go through this rather than touching {@link SimulationState} directly, so that every
 * change can be logged, and so that laying down a memory picks up the owning type's decay defaults
 * without each effect having to know about the spec.
 */
public interface EffectContext {

    SimulationState state();

    long tick();

    /** Expression context for the target currently being processed. */
    EvalContext evalContext();

    /** Random stream scoped to this effect application. */
    Rng rng();

    /** Id of the event whose effects are running, for the audit trail. */
    String sourceEventId();

    /**
     * Creates a memory, filling in the owner type's default decay model unless one is given.
     *
     * @param decayOverride model to use instead of the type default, or null
     */
    void injectMemory(ObjectState owner, ObjectState subject, String kind, double strength, double valence,
            double salience, Map<String, Double> features, DecayModel decayOverride);

    /**
     * Clamps a value to the declared range of a variable.
     *
     * <p>A variable's min and max are part of the model, not decoration: a stock declared over
     * zero to one is relied on by every sigmoid, chart axis and threshold downstream. An effect
     * that adds to a variable already near its ceiling would otherwise push it outside the range
     * its own specification promises.
     *
     * @param typeId the owning object type, or null for a global variable
     */
    double clampToDeclaredRange(String typeId, String variable, double value);

    /** Queues an event to fire later this tick, allowing one event to set off another. */
    void emitEvent(String eventId);

    /** Adds a new object of the given type, returning it. */
    ObjectState spawnObject(String typeId);

    /** Records a human-readable line in the run log, for "why did this happen" inspection. */
    void log(String message);
}
