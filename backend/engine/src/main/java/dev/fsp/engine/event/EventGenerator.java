package dev.fsp.engine.event;

import dev.fsp.engine.rng.Rng;
import java.util.List;
import java.util.Set;

/**
 * Decides how often an external event happens.
 *
 * <p>Spans the whole range the playground needs: from {@link Never} (nothing extra ever happens)
 * through fixed schedules and constant hazard rates to state-dependent and self-exciting regimes
 * where events arrive in clusters.
 */
public sealed interface EventGenerator {

    String kind();

    /**
     * Draws the number of occurrences for this tick.
     *
     * @param tick  current tick
     * @param random the event's random streams; a generator must not draw from anywhere else
     * @param state carried state from the previous tick
     */
    Outcome generate(long tick, EventRandom random, GeneratorState state);

    /** @param occurrences how many times the event fires this tick; @param nextState state to carry forward */
    record Outcome(int occurrences, GeneratorState nextState) {

        public static Outcome none(GeneratorState state) {
            return new Outcome(0, state);
        }
    }

    /** Nothing ever happens. The baseline a system is compared against. */
    record Never() implements EventGenerator {

        public static final Never INSTANCE = new Never();

        @Override
        public String kind() {
            return "never";
        }

        @Override
        public Outcome generate(long tick, EventRandom random, GeneratorState state) {
            return Outcome.none(state);
        }
    }

    /** Fires exactly at the listed ticks. Used for scripted interventions and A/B comparisons. */
    record FixedSchedule(Set<Long> ticks) implements EventGenerator {

        public FixedSchedule {
            ticks = Set.copyOf(ticks);
        }

        public static FixedSchedule at(long... ticks) {
            return new FixedSchedule(java.util.Arrays.stream(ticks).boxed().collect(java.util.stream.Collectors.toSet()));
        }

        @Override
        public String kind() {
            return "fixed-schedule";
        }

        @Override
        public Outcome generate(long tick, EventRandom random, GeneratorState state) {
            return ticks.contains(tick) ? new Outcome(1, state.recording(tick, 1)) : Outcome.none(state);
        }
    }

    /** Independent coin flip each tick: constant hazard, at most one occurrence per tick. */
    record Bernoulli(double probability) implements EventGenerator {

        public Bernoulli {
            if (probability < 0.0 || probability > 1.0) {
                throw new IllegalArgumentException("probability must be within [0, 1]: " + probability);
            }
        }

        @Override
        public String kind() {
            return "bernoulli";
        }

        @Override
        public Outcome generate(long tick, EventRandom random, GeneratorState state) {
            return random.tickStream(tick).nextBoolean(probability)
                    ? new Outcome(1, state.recording(tick, 1))
                    : Outcome.none(state);
        }
    }

    /** Poisson arrivals at a fixed mean rate; unlike {@link Bernoulli} it can fire twice in a tick. */
    record Poisson(double ratePerTick) implements EventGenerator {

        public Poisson {
            if (ratePerTick < 0.0) {
                throw new IllegalArgumentException("ratePerTick must not be negative: " + ratePerTick);
            }
        }

        @Override
        public String kind() {
            return "poisson";
        }

        @Override
        public Outcome generate(long tick, EventRandom random, GeneratorState state) {
            int count = random.tickStream(tick).nextPoisson(ratePerTick);
            return new Outcome(count, state.recording(tick, count));
        }
    }

    /**
     * Regular rhythm with optional jitter, for things like weekly meetings that rarely land on
     * exactly the same tick. The jitter for a period is drawn from that period's index, so the
     * schedule is stable under replay and checkpoint restore.
     *
     * @param interval ticks between occurrences
     * @param jitter   maximum absolute deviation, in ticks
     * @param offset   tick of the first scheduled occurrence
     */
    record Periodic(long interval, long jitter, long offset) implements EventGenerator {

        public Periodic {
            if (interval <= 0L) {
                throw new IllegalArgumentException("interval must be positive: " + interval);
            }
            if (jitter < 0L) {
                throw new IllegalArgumentException("jitter must not be negative: " + jitter);
            }
            if (jitter * 2 >= interval) {
                throw new IllegalArgumentException("jitter must be smaller than half the interval");
            }
        }

        public static Periodic every(long interval) {
            return new Periodic(interval, 0L, 0L);
        }

        @Override
        public String kind() {
            return "periodic";
        }

        @Override
        public Outcome generate(long tick, EventRandom random, GeneratorState state) {
            if (tick < offset) {
                return Outcome.none(state);
            }
            // Only the period whose jittered slot could contain this tick needs checking.
            long period = Math.floorDiv(tick - offset + jitter, interval);
            // The first period is clamped forward: negative jitter would otherwise schedule it
            // before the run began and silently drop that occurrence.
            long scheduled = Math.max(offset, offset + period * interval + jitterFor(period, random));
            return tick == scheduled ? new Outcome(1, state.recording(tick, 1)) : Outcome.none(state);
        }

        private long jitterFor(long period, EventRandom random) {
            if (jitter == 0L) {
                return 0L;
            }
            // Keyed by the period, not the tick, so every tick agrees on where this slot landed.
            return random.scheduleStream(period).nextInt((int) (2 * jitter + 1)) - jitter;
        }
    }

    /**
     * Regime switching: the system moves between named states, each with its own chance of the
     * event occurring. Models moods and seasons - a "tense period" where conflicts are likely,
     * followed by a calm one.
     */
    record MarkovChain(List<State> states) implements EventGenerator {

        /**
         * @param id                     state name, as shown in the editor
         * @param occurrenceProbability  chance of the event firing while in this state
         * @param transitions            outgoing transitions; leftover probability stays put
         */
        public record State(String id, double occurrenceProbability, List<Transition> transitions) {

            public State {
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("state id must not be blank");
                }
                if (occurrenceProbability < 0.0 || occurrenceProbability > 1.0) {
                    throw new IllegalArgumentException("occurrenceProbability must be within [0, 1]");
                }
                transitions = List.copyOf(transitions);
                double total = transitions.stream().mapToDouble(Transition::probability).sum();
                if (total > 1.0 + 1e-9) {
                    throw new IllegalArgumentException("transition probabilities out of " + id + " exceed 1: " + total);
                }
            }
        }

        public record Transition(String toState, double probability) {

            public Transition {
                if (probability < 0.0 || probability > 1.0) {
                    throw new IllegalArgumentException("probability must be within [0, 1]: " + probability);
                }
            }
        }

        public MarkovChain {
            states = List.copyOf(states);
            if (states.isEmpty()) {
                throw new IllegalArgumentException("at least one state is required");
            }
            Set<String> ids = states.stream().map(State::id).collect(java.util.stream.Collectors.toSet());
            if (ids.size() != states.size()) {
                throw new IllegalArgumentException("duplicate state ids");
            }
            for (State state : states) {
                for (Transition transition : state.transitions()) {
                    if (!ids.contains(transition.toState())) {
                        throw new IllegalArgumentException("unknown transition target: " + transition.toState());
                    }
                }
            }
        }

        public String initialStateId() {
            return states.getFirst().id();
        }

        @Override
        public String kind() {
            return "markov-chain";
        }

        @Override
        public Outcome generate(long tick, EventRandom random, GeneratorState state) {
            State current = stateById(state.markovState() == null ? initialStateId() : state.markovState());
            Rng rng = random.tickStream(tick);
            boolean fired = rng.nextBoolean(current.occurrenceProbability());
            String next = transition(current, rng.derive(1L));
            GeneratorState carried = state.withMarkovState(next);
            return fired ? new Outcome(1, carried.recording(tick, 1)) : Outcome.none(carried);
        }

        private State stateById(String id) {
            for (State state : states) {
                if (state.id().equals(id)) {
                    return state;
                }
            }
            throw new IllegalStateException("unknown markov state: " + id);
        }

        private String transition(State current, Rng rng) {
            double roll = rng.nextDouble();
            double cumulative = 0.0;
            for (Transition transition : current.transitions()) {
                cumulative += transition.probability();
                if (roll < cumulative) {
                    return transition.toState();
                }
            }
            return current.id();
        }
    }

    /**
     * Self-exciting arrivals: each occurrence raises the rate of the next, which then decays back
     * towards the baseline. Produces the clustering real crises have - one argument makes the next
     * one likelier for a while.
     *
     * @param baseRate   arrivals per tick with no recent history
     * @param excitation rate added per occurrence
     * @param decay      per-tick retention of accumulated excitation, in {@code [0, 1)}
     */
    record Burst(double baseRate, double excitation, double decay) implements EventGenerator {

        public Burst {
            if (baseRate < 0.0) {
                throw new IllegalArgumentException("baseRate must not be negative: " + baseRate);
            }
            if (excitation < 0.0) {
                throw new IllegalArgumentException("excitation must not be negative: " + excitation);
            }
            if (decay < 0.0 || decay >= 1.0) {
                throw new IllegalArgumentException("decay must be within [0, 1): " + decay);
            }
            // Branching ratio: expected number of further occurrences triggered by one occurrence.
            // At or above 1 the process is supercritical and the rate diverges instead of settling,
            // which is never what an author means and would run the simulation out of memory.
            double branchingRatio = excitation / (1.0 - decay);
            if (branchingRatio >= 1.0) {
                throw new IllegalArgumentException(
                        "excitation / (1 - decay) must stay below 1 or bursts never subside: " + branchingRatio);
            }
        }

        /** Expected occurrences triggered by a single occurrence; always below 1 by construction. */
        public double branchingRatio() {
            return excitation / (1.0 - decay);
        }

        /** Long-run mean arrivals per tick, once excitation has settled. */
        public double steadyStateRate() {
            return baseRate / (1.0 - branchingRatio());
        }

        @Override
        public String kind() {
            return "burst";
        }

        @Override
        public Outcome generate(long tick, EventRandom random, GeneratorState state) {
            double rate = baseRate + state.intensity();
            int count = random.tickStream(tick).nextPoisson(rate);
            double nextIntensity = state.intensity() * decay + excitation * count;
            GeneratorState carried = state.withIntensity(nextIntensity).recording(tick, count);
            return new Outcome(count, carried);
        }
    }
}
