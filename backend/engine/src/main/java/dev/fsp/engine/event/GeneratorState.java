package dev.fsp.engine.event;

/**
 * The carried-forward state of one event generator.
 *
 * <p>Most generators are memoryless and ignore this, but Markov and bursty ones cannot be a pure
 * function of the tick alone. Keeping their state here (rather than inside the generator record)
 * leaves generator definitions immutable and makes checkpointing a matter of serialising a handful
 * of small values.
 *
 * @param markovState       current state id, for {@link EventGenerator.MarkovChain}
 * @param intensity         self-excitation carried between ticks, for {@link EventGenerator.Burst}
 * @param lastOccurrenceTick tick of the most recent occurrence, or {@code Long.MIN_VALUE} if never
 * @param totalOccurrences  lifetime count, used to enforce occurrence caps
 */
public record GeneratorState(String markovState, double intensity, long lastOccurrenceTick, int totalOccurrences) {

    public static final GeneratorState INITIAL = new GeneratorState(null, 0.0, Long.MIN_VALUE, 0);

    public static GeneratorState startingIn(String markovState) {
        return new GeneratorState(markovState, 0.0, Long.MIN_VALUE, 0);
    }

    public GeneratorState withMarkovState(String next) {
        return new GeneratorState(next, intensity, lastOccurrenceTick, totalOccurrences);
    }

    public GeneratorState withIntensity(double next) {
        return new GeneratorState(markovState, next, lastOccurrenceTick, totalOccurrences);
    }

    /** Records that {@code count} occurrences happened at {@code tick}. */
    public GeneratorState recording(long tick, int count) {
        if (count <= 0) {
            return this;
        }
        return new GeneratorState(markovState, intensity, tick, totalOccurrences + count);
    }

    public boolean hasFired() {
        return lastOccurrenceTick != Long.MIN_VALUE;
    }

    public long ticksSinceLastOccurrence(long tick) {
        return hasFired() ? tick - lastOccurrenceTick : Long.MAX_VALUE;
    }
}
