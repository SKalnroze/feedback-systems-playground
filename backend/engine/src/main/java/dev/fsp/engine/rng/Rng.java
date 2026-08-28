package dev.fsp.engine.rng;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Deterministic random source scoped to a single (run, tick, stream, entity) coordinate.
 *
 * <p>Instances are cheap and disposable: create one where you need draws, discard it after.
 * Because the seed is derived from coordinates rather than carried forward, a run can be
 * resumed from a checkpoint holding nothing but {@code (runSeed, tick)} and will produce
 * exactly the numbers it would have produced had it never stopped.
 */
public final class Rng {

    private long state;

    private Rng(long seed) {
        this.state = seed;
    }

    public static Rng of(long runSeed, long tick, RngStream stream, long entity) {
        return new Rng(SplitMix64.seed(runSeed, tick, stream.id(), entity));
    }

    public static Rng of(long runSeed, long tick, RngStream stream) {
        return of(runSeed, tick, stream, 0L);
    }

    /** Derives a sub-stream, e.g. per-target draws inside one event evaluation. */
    public Rng derive(long salt) {
        return new Rng(SplitMix64.seed(state, salt));
    }

    public long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        return SplitMix64.mix(state);
    }

    /** Uniform in {@code [0, 1)}. */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Uniform in {@code [origin, bound)}. */
    public double nextDouble(double origin, double bound) {
        return origin + nextDouble() * (bound - origin);
    }

    /** Uniform in {@code [0, bound)} using Lemire's debiased multiply-shift. */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        long product = (nextLong() >>> 32) * bound;
        long low = product & 0xFFFFFFFFL;
        if (low < bound) {
            long threshold = Integer.toUnsignedLong(-bound) % bound;
            while (low < threshold) {
                product = (nextLong() >>> 32) * bound;
                low = product & 0xFFFFFFFFL;
            }
        }
        return (int) (product >>> 32);
    }

    public boolean nextBoolean(double probability) {
        if (probability <= 0.0) {
            return false;
        }
        if (probability >= 1.0) {
            return true;
        }
        return nextDouble() < probability;
    }

    /** Standard normal via the Marsaglia polar method (both variates would be wasteful; one is kept). */
    public double nextGaussian() {
        double u;
        double v;
        double s;
        do {
            u = nextDouble() * 2.0 - 1.0;
            v = nextDouble() * 2.0 - 1.0;
            s = u * u + v * v;
        } while (s >= 1.0 || s == 0.0);
        return u * Math.sqrt(-2.0 * Math.log(s) / s);
    }

    public double nextGaussian(double mean, double stdDev) {
        return mean + stdDev * nextGaussian();
    }

    /** Exponential inter-arrival time for the given rate (events per tick). */
    public double nextExponential(double rate) {
        if (rate <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return -Math.log(1.0 - nextDouble()) / rate;
    }

    /**
     * Poisson count for the given mean. Knuth's product method below 30, where it is exact and
     * cheap; a normal approximation above it, where Knuth's loop cost grows linearly with lambda.
     */
    public int nextPoisson(double lambda) {
        if (lambda <= 0.0) {
            return 0;
        }
        if (lambda < 30.0) {
            double limit = Math.exp(-lambda);
            double product = 1.0;
            int count = 0;
            do {
                count++;
                product *= nextDouble();
            } while (product > limit);
            return count - 1;
        }
        return Math.max(0, (int) Math.round(nextGaussian(lambda, Math.sqrt(lambda))));
    }

    public <T> T pick(List<T> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("cannot pick from an empty list");
        }
        return items.get(nextInt(items.size()));
    }

    /**
     * Draws {@code k} distinct items in a stable order (partial Fisher-Yates on a copy).
     * Returns fewer than {@code k} only when the input is smaller.
     */
    public <T> List<T> sample(Collection<T> items, int k) {
        List<T> pool = new ArrayList<>(items);
        int take = Math.min(k, pool.size());
        for (int i = 0; i < take; i++) {
            int j = i + nextInt(pool.size() - i);
            T tmp = pool.get(i);
            pool.set(i, pool.get(j));
            pool.set(j, tmp);
        }
        return List.copyOf(pool.subList(0, take));
    }

    /** Shuffles a copy; used where the whole ordering matters (e.g. interaction pairing). */
    public <T> List<T> shuffled(Collection<T> items) {
        return sample(items, items.size());
    }
}
