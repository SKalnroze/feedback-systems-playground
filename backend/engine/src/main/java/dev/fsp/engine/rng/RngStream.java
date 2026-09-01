package dev.fsp.engine.rng;

/**
 * Independent random streams. Each phase of the tick pipeline draws from its own stream so
 * that adding a draw in one phase cannot shift the numbers seen by another phase.
 */
public enum RngStream {
    EVENT_GENERATION,
    EVENT_SCHEDULE,
    EVENT_TARGETING,
    EVENT_EFFECT,
    TRIGGER_STOCHASTIC,
    INTERACTION_SELECTION,
    INTERACTION_OUTCOME,
    OBSERVATION,
    MEMORY_NOISE,
    MODULE_CUSTOM,
    /**
     * Pairing members across a link whose coupling is random.
     *
     * <p>Appended rather than inserted: the stream id is the ordinal, so putting a new value
     * anywhere else would renumber the others and change every existing run's numbers.
     */
    LINK_COUPLING;

    public int id() {
        return ordinal();
    }
}
