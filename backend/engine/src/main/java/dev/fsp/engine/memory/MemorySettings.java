package dev.fsp.engine.memory;

/**
 * How one object type handles remembering.
 *
 * @param defaultDecay        decay model applied to memories that do not name their own
 * @param retrievalThreshold  strength below which a memory can no longer be retrieved
 * @param capacity            maximum retained memories; the weakest are dropped first, 0 means unlimited
 * @param pruneForgotten      whether sub-threshold memories are deleted or merely unretrievable
 * @param similarityThreshold cosine similarity at which two memories count as interfering, and at
 *                            which a cue is considered to match a memory
 */
public record MemorySettings(DecayModel defaultDecay, double retrievalThreshold, int capacity, boolean pruneForgotten,
        double similarityThreshold) {

    public static final MemorySettings DEFAULT = new MemorySettings(DecayModel.Exponential.ofHalfLife(50.0), 0.05, 0,
            false, 0.6);

    public MemorySettings {
        if (decayIsMissing(defaultDecay)) {
            throw new IllegalArgumentException("defaultDecay is required");
        }
        if (retrievalThreshold < 0.0 || retrievalThreshold > 1.0) {
            throw new IllegalArgumentException("retrievalThreshold must be within [0, 1]: " + retrievalThreshold);
        }
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative: " + capacity);
        }
        if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
            throw new IllegalArgumentException("similarityThreshold must be within [0, 1]: " + similarityThreshold);
        }
    }

    public boolean hasCapacityLimit() {
        return capacity > 0;
    }

    /**
     * Whether similar memories suppress one another under this configuration.
     *
     * <p>Worth asking before doing the work: counting interfering memories is quadratic in how many
     * an object holds, and most systems leave interference switched off.
     */
    public boolean usesInterference() {
        return defaultDecay.common().interference() > 0.0;
    }

    private static boolean decayIsMissing(DecayModel model) {
        return model == null;
    }
}
