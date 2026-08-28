package dev.fsp.engine.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fsp.engine.event.EventGenerator.Bernoulli;
import dev.fsp.engine.event.EventGenerator.Burst;
import dev.fsp.engine.event.EventGenerator.FixedSchedule;
import dev.fsp.engine.event.EventGenerator.MarkovChain;
import dev.fsp.engine.event.EventGenerator.Never;
import dev.fsp.engine.event.EventGenerator.Outcome;
import dev.fsp.engine.event.EventGenerator.Periodic;
import dev.fsp.engine.event.EventGenerator.Poisson;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EventGeneratorTest {

    private static final long SEED = 20260828L;
    private static final EventRandom RANDOM = EventRandom.forEvent(SEED, "test-event");

    /** Runs a generator over a tick range, carrying its state, and returns the tick of every occurrence. */
    private static List<Long> occurrenceTicks(EventGenerator generator, long ticks) {
        List<Long> fired = new ArrayList<>();
        GeneratorState state = GeneratorState.INITIAL;
        for (long tick = 0; tick < ticks; tick++) {
            Outcome outcome = generator.generate(tick, RANDOM, state);
            for (int i = 0; i < outcome.occurrences(); i++) {
                fired.add(tick);
            }
            state = outcome.nextState();
        }
        return fired;
    }

    private static int totalOccurrences(EventGenerator generator, long ticks) {
        return occurrenceTicks(generator, ticks).size();
    }

    @Nested
    @DisplayName("never")
    class NeverGenerator {

        @Test
        void producesNothingAtAll() {
            assertThat(occurrenceTicks(Never.INSTANCE, 10_000)).isEmpty();
        }

        @Test
        void leavesStateUntouched() {
            Outcome outcome = Never.INSTANCE.generate(5L, RANDOM,
                    GeneratorState.INITIAL);

            assertThat(outcome.nextState()).isEqualTo(GeneratorState.INITIAL);
            assertThat(outcome.nextState().hasFired()).isFalse();
        }
    }

    @Nested
    @DisplayName("fixed schedule")
    class Scheduled {

        @Test
        void firesExactlyAtTheListedTicks() {
            assertThat(occurrenceTicks(FixedSchedule.at(3L, 17L, 40L), 100)).containsExactly(3L, 17L, 40L);
        }

        @Test
        void tracksTheLastOccurrence() {
            GeneratorState state = GeneratorState.INITIAL;
            EventGenerator generator = FixedSchedule.at(2L);
            for (long tick = 0; tick <= 5; tick++) {
                state = generator.generate(tick, RANDOM, state).nextState();
            }

            assertThat(state.lastOccurrenceTick()).isEqualTo(2L);
            assertThat(state.totalOccurrences()).isEqualTo(1);
            assertThat(state.ticksSinceLastOccurrence(5L)).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("bernoulli")
    class BernoulliGenerator {

        @Test
        void firesAtRoughlyTheRequestedRate() {
            int fired = totalOccurrences(new Bernoulli(0.1), 20_000);

            assertThat(fired / 20_000.0).isCloseTo(0.1, Offset.offset(0.01));
        }

        @Test
        void neverFiresTwiceInOneTick() {
            GeneratorState state = GeneratorState.INITIAL;
            EventGenerator generator = new Bernoulli(0.9);
            for (long tick = 0; tick < 500; tick++) {
                Outcome outcome = generator.generate(tick, RANDOM, state);
                assertThat(outcome.occurrences()).isLessThanOrEqualTo(1);
                state = outcome.nextState();
            }
        }

        @Test
        void handlesCertaintyAtBothEnds() {
            assertThat(occurrenceTicks(new Bernoulli(0.0), 100)).isEmpty();
            assertThat(occurrenceTicks(new Bernoulli(1.0), 100)).hasSize(100);
        }

        @Test
        void rejectsProbabilitiesOutsideUnitInterval() {
            assertThatThrownBy(() -> new Bernoulli(1.5)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Bernoulli(-0.1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("poisson")
    class PoissonGenerator {

        @Test
        void matchesTheRequestedMeanRate() {
            int fired = totalOccurrences(new Poisson(0.3), 20_000);

            assertThat(fired / 20_000.0).isCloseTo(0.3, Offset.offset(0.02));
        }

        @Test
        void canFireMoreThanOnceInATick() {
            List<Long> fired = occurrenceTicks(new Poisson(2.0), 200);

            assertThat(fired).hasSizeGreaterThan(200);
            assertThat(fired.stream().distinct().count()).isLessThan(fired.size());
        }

        @Test
        void rejectsNegativeRate() {
            assertThatThrownBy(() -> new Poisson(-1.0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("periodic")
    class PeriodicGenerator {

        @Test
        void firesOnAStrictRhythmWithoutJitter() {
            assertThat(occurrenceTicks(Periodic.every(7L), 30)).containsExactly(0L, 7L, 14L, 21L, 28L);
        }

        @Test
        void respectsTheOffset() {
            assertThat(occurrenceTicks(new Periodic(10L, 0L, 3L), 35)).containsExactly(3L, 13L, 23L, 33L);
        }

        @Test
        void jitterKeepsOneOccurrencePerPeriodInsideTheAllowedWindow() {
            List<Long> fired = occurrenceTicks(new Periodic(20L, 4L, 0L), 400);

            assertThat(fired).hasSizeBetween(19, 21);
            for (long tick : fired) {
                long nearestSlot = Math.round(tick / 20.0) * 20L;
                assertThat(Math.abs(tick - nearestSlot)).isLessThanOrEqualTo(4L);
            }
            assertThat(fired).doesNotHaveDuplicates();
        }

        @Test
        void jitterIsStableUnderReplay() {
            assertThat(occurrenceTicks(new Periodic(20L, 4L, 0L), 200))
                    .isEqualTo(occurrenceTicks(new Periodic(20L, 4L, 0L), 200));
        }

        @Test
        void rejectsImpossibleConfigurations() {
            assertThatThrownBy(() -> new Periodic(0L, 0L, 0L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Periodic(10L, -1L, 0L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Periodic(10L, 5L, 0L)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("half the interval");
        }
    }

    @Nested
    @DisplayName("markov chain")
    class MarkovGenerator {

        private final MarkovChain chain = new MarkovChain(List.of(
                new MarkovChain.State("calm", 0.01, List.of(new MarkovChain.Transition("tense", 0.05))),
                new MarkovChain.State("tense", 0.4, List.of(new MarkovChain.Transition("calm", 0.2)))));

        @Test
        void staysInTheFirstStateUntilItTransitions() {
            assertThat(chain.initialStateId()).isEqualTo("calm");

            Outcome outcome = chain.generate(0L, RANDOM, GeneratorState.INITIAL);

            assertThat(outcome.nextState().markovState()).isIn("calm", "tense");
        }

        @Test
        void visitsBothRegimesOverALongRun() {
            GeneratorState state = GeneratorState.INITIAL;
            List<String> visited = new ArrayList<>();
            for (long tick = 0; tick < 2_000; tick++) {
                state = chain.generate(tick, RANDOM, state).nextState();
                visited.add(state.markovState());
            }

            assertThat(visited).contains("calm", "tense");
        }

        @Test
        void firesMoreOftenInTheHighProbabilityRegime() {
            MarkovChain alwaysTense = new MarkovChain(List.of(new MarkovChain.State("tense", 0.5, List.of())));
            MarkovChain alwaysCalm = new MarkovChain(List.of(new MarkovChain.State("calm", 0.02, List.of())));

            assertThat(totalOccurrences(alwaysTense, 2_000)).isGreaterThan(totalOccurrences(alwaysCalm, 2_000));
        }

        @Test
        void rejectsMalformedChains() {
            assertThatThrownBy(() -> new MarkovChain(List.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MarkovChain(List.of(new MarkovChain.State("a", 0.1, List.of()),
                    new MarkovChain.State("a", 0.1, List.of())))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicate");
            assertThatThrownBy(() -> new MarkovChain(
                    List.of(new MarkovChain.State("a", 0.1, List.of(new MarkovChain.Transition("ghost", 0.5))))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown transition target");
            assertThatThrownBy(() -> new MarkovChain.State("a", 0.1,
                    List.of(new MarkovChain.Transition("a", 0.7), new MarkovChain.Transition("a", 0.7))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceed 1");
            assertThatThrownBy(() -> new MarkovChain.State("a", 1.5, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("burst")
    class BurstGenerator {

        @Test
        void clustersOccurrencesMoreThanAPoissonOfTheSameMean() {
            List<Long> bursty = occurrenceTicks(new Burst(0.02, 0.12, 0.85), 20_000);
            List<Long> flat = occurrenceTicks(new Poisson(bursty.size() / 20_000.0), 20_000);

            assertThat(meanGap(bursty)).isLessThan(meanGap(flat));
        }

        @Test
        void intensityDecaysBackTowardsTheBaseline() {
            Burst generator = new Burst(0.0, 0.4, 0.5);
            GeneratorState excited = GeneratorState.INITIAL.withIntensity(4.0);

            GeneratorState after = generator.generate(1L, RANDOM, excited)
                    .nextState();

            assertThat(after.intensity()).isGreaterThanOrEqualTo(2.0);
        }

        @Test
        void reportsItsBranchingRatioAndSteadyStateRate() {
            Burst generator = new Burst(0.05, 0.25, 0.5);

            assertThat(generator.branchingRatio()).isCloseTo(0.5, Offset.offset(1e-9));
            assertThat(generator.steadyStateRate()).isCloseTo(0.1, Offset.offset(1e-9));
        }

        @Test
        void refusesSupercriticalConfigurationsThatWouldNeverSubside() {
            assertThatThrownBy(() -> new Burst(0.02, 0.35, 0.85)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("never subside");
        }

        @Test
        void withoutExcitationItBehavesLikePoisson() {
            int fired = totalOccurrences(new Burst(0.25, 0.0, 0.0), 20_000);

            assertThat(fired / 20_000.0).isCloseTo(0.25, Offset.offset(0.02));
        }

        @Test
        void rejectsInvalidParameters() {
            assertThatThrownBy(() -> new Burst(-1.0, 0.1, 0.5)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Burst(0.1, -0.1, 0.5)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Burst(0.1, 0.1, 1.0)).isInstanceOf(IllegalArgumentException.class);
        }

        /** Mean gap between consecutive occurrences; clustered arrivals give a smaller value. */
        private double meanGap(List<Long> ticks) {
            if (ticks.size() < 2) {
                return Double.MAX_VALUE;
            }
            long total = 0;
            for (int i = 1; i < ticks.size(); i++) {
                total += ticks.get(i) - ticks.get(i - 1);
            }
            return total / (double) (ticks.size() - 1);
        }
    }

    @Nested
    @DisplayName("shared behaviour")
    class Shared {

        @Test
        void replayingTheSameSeedReproducesTheSameOccurrences() {
            EventGenerator generator = new Burst(0.05, 0.15, 0.8);

            assertThat(occurrenceTicks(generator, 1_000)).isEqualTo(occurrenceTicks(generator, 1_000));
        }

        @Test
        void everyGeneratorReportsADistinctKind() {
            List<EventGenerator> generators = List.of(Never.INSTANCE, FixedSchedule.at(1L), new Bernoulli(0.1),
                    new Poisson(0.1), Periodic.every(5L),
                    new MarkovChain(List.of(new MarkovChain.State("a", 0.1, List.of()))), new Burst(0.1, 0.1, 0.5));

            assertThat(generators.stream().map(EventGenerator::kind).distinct().count()).isEqualTo(generators.size());
        }
    }
}
