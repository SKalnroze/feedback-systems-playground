package dev.fsp.engine.rng;

/**
 * SplitMix64 mixing function. Used both as a stream cipher for {@link Rng} and as a
 * hash for deriving per-draw seeds from (runSeed, tick, stream, entity) tuples.
 *
 * <p>The whole engine's determinism rests on this: a draw is a pure function of its
 * coordinates, so nothing about thread scheduling, iteration order or restart history
 * can change a result.
 */
public final class SplitMix64 {

    private SplitMix64() {
    }

    /** The SplitMix64 finalizer (a bijection on 64 bits with good avalanche). */
    public static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Combines an arbitrary number of coordinates into a single seed. */
    public static long seed(long... coordinates) {
        long acc = 0x243F6A8885A308D3L;
        for (long c : coordinates) {
            acc = mix(acc ^ mix(c));
        }
        return acc;
    }
}
