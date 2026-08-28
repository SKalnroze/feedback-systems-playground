package dev.fsp.engine.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * How the strength of a memory changes as ticks pass.
 *
 * <p>Every model is a pure function of {@link Trace} and the current tick, which is what lets a
 * memory's whole history be recomputed on demand rather than stepped forward and stored.
 *
 * <p>Three concerns are shared by all models and handled once in {@link #strengthAt}:
 * <ul>
 *   <li><b>consolidation</b> - each reactivation adds its boost to the effective initial strength;
 *   <li><b>interference</b> - similar memories held by the same owner suppress retrieval;
 *   <li><b>floor</b> - a permanent trace that decay can never erase.
 * </ul>
 * Implementations only supply the shape of the forgetting curve, via {@link #curve}.
 */
public sealed interface DecayModel {

    /** Stable discriminator used in the JSON spec and in the editor palette. */
    String kind();

    /** Parameters shared by every model. */
    DecayCommon common();

    /**
     * The forgetting curve itself.
     *
     * @param trace   the memory being evaluated
     * @param elapsed ticks since the decay clock's origin, never negative
     * @param initial effective initial strength, after consolidation boosts
     */
    double curve(Trace trace, long elapsed, double initial);

    /** Strength of {@code trace} at {@code tick}, in {@code [0, 1]}. */
    default double strengthAt(Trace trace, long tick) {
        double initial = effectiveInitialStrength(trace);
        long elapsed = Math.max(0L, tick - trace.originTick());
        double raw = curve(trace, elapsed, initial);
        double interfered = raw / (1.0 + common().interference() * trace.similarCount());
        return Math.clamp(Math.max(interfered, common().floor()), 0.0, 1.0);
    }

    /** True once strength has fallen to the point where the memory is no longer retrievable. */
    default boolean isForgotten(Trace trace, long tick, double retrievalThreshold) {
        return strengthAt(trace, tick) < retrievalThreshold;
    }

    /** Initial strength plus every consolidation boost earned by reactivation, capped at 1. */
    default double effectiveInitialStrength(Trace trace) {
        return Math.clamp(trace.initialStrength() + trace.totalBoost(), 0.0, 1.0);
    }

    /**
     * Parameters every model shares.
     *
     * @param floor        strength never drops below this, modelling a permanent trace
     * @param interference each similar memory divides strength by {@code 1 + interference}
     */
    record DecayCommon(double floor, double interference) {

        public static final DecayCommon NONE = new DecayCommon(0.0, 0.0);

        public DecayCommon {
            if (floor < 0.0 || floor > 1.0) {
                throw new IllegalArgumentException("floor must be within [0, 1]: " + floor);
            }
            if (interference < 0.0) {
                throw new IllegalArgumentException("interference must not be negative: " + interference);
            }
        }

        public static DecayCommon floor(double floor) {
            return new DecayCommon(floor, 0.0);
        }
    }

    /** No forgetting at all: the memory is as vivid on the last tick as on the first. */
    record NoDecay(DecayCommon common) implements DecayModel {

        public static final NoDecay INSTANCE = new NoDecay(DecayCommon.NONE);

        @Override
        public String kind() {
            return "none";
        }

        @Override
        public double curve(Trace trace, long elapsed, double initial) {
            return initial;
        }
    }

    /**
     * Classic exponential forgetting: a fixed proportion is lost per unit time, so strength
     * halves every {@code halfLife} ticks. The default choice when nothing better is known.
     */
    record Exponential(double halfLife, DecayCommon common) implements DecayModel {

        public Exponential {
            if (halfLife <= 0.0) {
                throw new IllegalArgumentException("halfLife must be positive: " + halfLife);
            }
        }

        public static Exponential ofHalfLife(double halfLife) {
            return new Exponential(halfLife, DecayCommon.NONE);
        }

        @Override
        public String kind() {
            return "exponential";
        }

        @Override
        public double curve(Trace trace, long elapsed, double initial) {
            return initial * Math.pow(2.0, -elapsed / halfLife);
        }
    }

    /**
     * Power-law forgetting (Wickelgren, Wixted): fast early loss, then a long tail. Fits human
     * retention data better than the exponential, and keeps old memories faintly alive for a
     * very long time.
     */
    record PowerLaw(double exponent, DecayCommon common) implements DecayModel {

        public PowerLaw {
            if (exponent <= 0.0) {
                throw new IllegalArgumentException("exponent must be positive: " + exponent);
            }
        }

        public static PowerLaw ofExponent(double exponent) {
            return new PowerLaw(exponent, DecayCommon.NONE);
        }

        @Override
        public String kind() {
            return "power-law";
        }

        @Override
        public double curve(Trace trace, long elapsed, double initial) {
            return initial * Math.pow(1.0 + elapsed, -exponent);
        }
    }

    /**
     * Ebbinghaus retention with a spacing effect: exponential in shape, but the time constant
     * (stability) grows with every reactivation, so a memory rehearsed at intervals decays ever
     * more slowly.
     *
     * @param baseStability   time constant of a memory that has never been reactivated
     * @param stabilityGrowth stability added per unit of accumulated reactivation gain
     */
    record Ebbinghaus(double baseStability, double stabilityGrowth, DecayCommon common) implements DecayModel {

        public Ebbinghaus {
            if (baseStability <= 0.0) {
                throw new IllegalArgumentException("baseStability must be positive: " + baseStability);
            }
            if (stabilityGrowth < 0.0) {
                throw new IllegalArgumentException("stabilityGrowth must not be negative: " + stabilityGrowth);
            }
        }

        @Override
        public String kind() {
            return "ebbinghaus";
        }

        @Override
        public double curve(Trace trace, long elapsed, double initial) {
            return initial * Math.exp(-elapsed / stability(trace));
        }

        /** Current time constant, including everything reactivation has added to it. */
        public double stability(Trace trace) {
            return baseStability + stabilityGrowth * trace.totalStabilityGain();
        }
    }

    /** Constant absolute loss per tick. Unrealistic for recall, useful for resource-like traces. */
    record Linear(double ratePerTick, DecayCommon common) implements DecayModel {

        public Linear {
            if (ratePerTick < 0.0) {
                throw new IllegalArgumentException("ratePerTick must not be negative: " + ratePerTick);
            }
        }

        @Override
        public String kind() {
            return "linear";
        }

        @Override
        public double curve(Trace trace, long elapsed, double initial) {
            return Math.max(0.0, initial - ratePerTick * elapsed);
        }
    }

    /**
     * Plateau then cliff: strength holds near its initial value, then falls away around
     * {@code midpoint}. Models memories that stay intact until some horizon and then go quickly.
     *
     * @param steepness how abrupt the drop is; larger is sharper
     * @param midpoint  tick offset at which half the strength is gone
     */
    record Logistic(double steepness, double midpoint, DecayCommon common) implements DecayModel {

        public Logistic {
            if (steepness <= 0.0) {
                throw new IllegalArgumentException("steepness must be positive: " + steepness);
            }
            if (midpoint <= 0.0) {
                throw new IllegalArgumentException("midpoint must be positive: " + midpoint);
            }
        }

        @Override
        public String kind() {
            return "logistic";
        }

        @Override
        public double curve(Trace trace, long elapsed, double initial) {
            // Normalised so that elapsed == 0 yields exactly the initial strength.
            double normaliser = 1.0 + Math.exp(-steepness * midpoint);
            return initial * normaliser / (1.0 + Math.exp(steepness * (elapsed - midpoint)));
        }
    }

    /**
     * Discrete ladder: strength holds flat, then drops to a new multiple of its initial value at
     * each configured age. Models coarse memory stages (vivid, then gist, then a name only).
     */
    record StepThreshold(List<Step> steps, DecayCommon common) implements DecayModel {

        /**
         * @param afterTicks age at which this step takes effect
         * @param retention  multiplier applied to the initial strength from then on
         */
        public record Step(long afterTicks, double retention) {

            public Step {
                if (afterTicks < 0L) {
                    throw new IllegalArgumentException("afterTicks must not be negative: " + afterTicks);
                }
                if (retention < 0.0 || retention > 1.0) {
                    throw new IllegalArgumentException("retention must be within [0, 1]: " + retention);
                }
            }
        }

        public StepThreshold {
            if (steps == null || steps.isEmpty()) {
                throw new IllegalArgumentException("at least one step is required");
            }
            List<Step> sorted = new ArrayList<>(steps);
            sorted.sort(Comparator.comparingLong(Step::afterTicks));
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).afterTicks() == sorted.get(i - 1).afterTicks()) {
                    throw new IllegalArgumentException("duplicate step at tick " + sorted.get(i).afterTicks());
                }
            }
            steps = List.copyOf(sorted);
        }

        @Override
        public String kind() {
            return "step-threshold";
        }

        @Override
        public double curve(Trace trace, long elapsed, double initial) {
            double retention = 1.0;
            for (Step step : steps) {
                if (elapsed >= step.afterTicks()) {
                    retention = step.retention();
                } else {
                    break;
                }
            }
            return initial * retention;
        }
    }

    /**
     * ACT-R base-level activation. Every presentation of the memory (its creation and each
     * reactivation) contributes {@code lag^-decayExponent} to an activation sum; retrieval
     * probability is a logistic function of the log of that sum.
     *
     * <p>This is the only model where reactivation is intrinsic rather than a bolt-on: recency
     * and frequency of rehearsal both fall out of the same expression, which is why spaced
     * repetition works under it without any extra parameters.
     *
     * @param decayExponent {@code d} in the base-level equation; 0.5 is the ACT-R default
     * @param threshold     activation at which retrieval is even odds
     * @param noiseScale    logistic spread; smaller makes the retrieval boundary sharper
     */
    record ActRBaseLevel(double decayExponent, double threshold, double noiseScale, DecayCommon common)
            implements DecayModel {

        public ActRBaseLevel {
            if (decayExponent <= 0.0) {
                throw new IllegalArgumentException("decayExponent must be positive: " + decayExponent);
            }
            if (noiseScale <= 0.0) {
                throw new IllegalArgumentException("noiseScale must be positive: " + noiseScale);
            }
        }

        public static ActRBaseLevel standard() {
            return new ActRBaseLevel(0.5, 0.0, 0.4, DecayCommon.NONE);
        }

        @Override
        public String kind() {
            return "act-r-base-level";
        }

        @Override
        public double curve(Trace trace, long elapsed, double initial) {
            long now = trace.originTick() + elapsed;
            double sum = Math.pow(lag(now, trace.createdTick()), -decayExponent);
            int counted = 0;
            for (Reactivation reactivation : trace.reactivations()) {
                if (reactivation.tick() <= now) {
                    sum += Math.pow(lag(now, reactivation.tick()), -decayExponent);
                    counted++;
                }
            }
            if (!trace.hasCompleteHistory()) {
                sum += approximateDroppedPresentations(trace, now, counted);
            }
            double activation = Math.log(sum);
            double retrievalProbability = 1.0 / (1.0 + Math.exp((threshold - activation) / noiseScale));
            return initial * retrievalProbability;
        }

        /**
         * ACT-R's optimised-learning approximation for presentations no longer held individually.
         *
         * <p>Treats the dropped rehearsals as spread evenly between the memory's creation and the
         * oldest one still retained, and integrates the power function over that span instead of
         * summing it term by term. This is the standard approximation from the ACT-R literature and
         * it is what keeps strength evaluation cheap for a memory rehearsed thousands of times.
         */
        private double approximateDroppedPresentations(Trace trace, long now, int countedRecent) {
            int dropped = trace.reactivationCount() - countedRecent;
            if (dropped <= 0) {
                return 0.0;
            }
            double oldestLag = lag(now, trace.createdTick());
            double newestLag = lag(now, trace.oldestRetainedReactivationTick());
            if (oldestLag - newestLag < 1e-9) {
                return dropped * Math.pow(oldestLag, -decayExponent);
            }
            double exponent = 1.0 - decayExponent;
            if (Math.abs(exponent) < 1e-9) {
                return dropped * (Math.log(oldestLag) - Math.log(newestLag)) / (oldestLag - newestLag);
            }
            return dropped * (Math.pow(oldestLag, exponent) - Math.pow(newestLag, exponent))
                    / (exponent * (oldestLag - newestLag));
        }

        /** Lag is floored at one tick: a memory presented this very tick has finite activation. */
        private static double lag(long now, long presentedAt) {
            return Math.max(1.0, now - presentedAt);
        }
    }
}
