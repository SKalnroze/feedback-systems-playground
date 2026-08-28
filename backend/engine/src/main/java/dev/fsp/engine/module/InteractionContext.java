package dev.fsp.engine.module;

import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.Map;

/**
 * What a module's interaction rule may do during a tick.
 *
 * <p>The rule decides who interacts with whom and what it means; the engine keeps control of
 * randomness, memory creation and the audit log, so module code cannot accidentally break
 * reproducibility.
 */
public interface InteractionContext {

    SimulationState state();

    long tick();

    /** A stream scoped to this rule, tick and stream id. Never construct an {@code Rng} directly. */
    Rng rng(RngStream stream);

    /** Configured parameter from the spec, or {@code fallback} when the author left it unset. */
    double param(String name, double fallback);

    /**
     * Records an interaction memory for {@code owner} about {@code subject}. The owning type's
     * decay settings apply unless {@code decayOverride} is given.
     */
    void injectMemory(ObjectState owner, ObjectState subject, String kind, double strength, double valence,
            double salience, Map<String, Double> features, DecayModel decayOverride);

    /**
     * Marks an object as having been involved in something this tick, which is what
     * {@code SubjectPresent} triggers key off.
     */
    void markInPlay(ObjectState object);

    /**
     * Adds to this tick's cue vector. Cue-similarity triggers compare memories against it, so a
     * rule that stages an argument should add the features an argument has.
     */
    void addCue(String feature, double weight);

    /** Appends to the run log; shown in the UI's event stream. */
    void log(String message);
}
