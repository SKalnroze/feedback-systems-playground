package dev.fsp.engine.rng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RngTest {

    private static final Offset<Double> TOLERANCE = Offset.offset(0.01);

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        void sameCoordinatesProduceSameSequence() {
            List<Double> first = draw(Rng.of(42L, 7L, RngStream.EVENT_GENERATION, 3L));
            List<Double> second = draw(Rng.of(42L, 7L, RngStream.EVENT_GENERATION, 3L));

            assertThat(first).isEqualTo(second);
        }

        @Test
        void differentTickProducesDifferentSequence() {
            assertThat(draw(Rng.of(42L, 7L, RngStream.EVENT_GENERATION, 3L)))
                    .isNotEqualTo(draw(Rng.of(42L, 8L, RngStream.EVENT_GENERATION, 3L)));
        }

        @Test
        void differentStreamProducesDifferentSequence() {
            assertThat(draw(Rng.of(42L, 7L, RngStream.EVENT_GENERATION, 3L)))
                    .isNotEqualTo(draw(Rng.of(42L, 7L, RngStream.EVENT_TARGETING, 3L)));
        }

        @Test
        void differentEntityProducesDifferentSequence() {
            assertThat(draw(Rng.of(42L, 7L, RngStream.EVENT_GENERATION, 3L)))
                    .isNotEqualTo(draw(Rng.of(42L, 7L, RngStream.EVENT_GENERATION, 4L)));
        }

        @Test
        void derivedStreamsAreIndependentOfDrawOrder() {
            Rng parent = Rng.of(1L, 1L, RngStream.EVENT_EFFECT);
            List<Double> a = draw(parent.derive(10L));
            List<Double> b = draw(parent.derive(20L));

            Rng parentAgain = Rng.of(1L, 1L, RngStream.EVENT_EFFECT);
            List<Double> bFirst = draw(parentAgain.derive(20L));
            List<Double> aSecond = draw(parentAgain.derive(10L));

            assertThat(a).isEqualTo(aSecond);
            assertThat(b).isEqualTo(bFirst);
        }

        private List<Double> draw(Rng rng) {
            return IntStream.range(0, 16).mapToObj(i -> rng.nextDouble()).toList();
        }
    }

    @Nested
    @DisplayName("distributions")
    class Distributions {

        @Test
        void nextDoubleStaysInUnitInterval() {
            Rng rng = Rng.of(5L, 0L, RngStream.MEMORY_NOISE);
            for (int i = 0; i < 100_000; i++) {
                double value = rng.nextDouble();
                assertThat(value).isGreaterThanOrEqualTo(0.0).isLessThan(1.0);
            }
        }

        @Test
        void nextDoubleIsRoughlyUniform() {
            Rng rng = Rng.of(5L, 0L, RngStream.MEMORY_NOISE);
            int[] buckets = new int[10];
            for (int i = 0; i < 100_000; i++) {
                buckets[(int) (rng.nextDouble() * 10)]++;
            }
            for (int count : buckets) {
                assertThat(count).isBetween(9_000, 11_000);
            }
        }

        @Test
        void nextDoubleRespectsExplicitRange() {
            Rng rng = Rng.of(5L, 0L, RngStream.MEMORY_NOISE);
            for (int i = 0; i < 10_000; i++) {
                assertThat(rng.nextDouble(-2.0, 3.0)).isBetween(-2.0, 3.0);
            }
        }

        @Test
        void nextIntCoversTheWholeRange() {
            Rng rng = Rng.of(5L, 0L, RngStream.EVENT_TARGETING);
            int[] buckets = new int[7];
            for (int i = 0; i < 70_000; i++) {
                buckets[rng.nextInt(7)]++;
            }
            for (int count : buckets) {
                assertThat(count).isBetween(9_000, 11_000);
            }
        }

        @Test
        void nextIntRejectsNonPositiveBound() {
            Rng rng = Rng.of(5L, 0L, RngStream.EVENT_TARGETING);
            assertThatThrownBy(() -> rng.nextInt(0)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nextBooleanMatchesRequestedProbability() {
            Rng rng = Rng.of(11L, 0L, RngStream.TRIGGER_STOCHASTIC);
            int hits = 0;
            for (int i = 0; i < 100_000; i++) {
                if (rng.nextBoolean(0.25)) {
                    hits++;
                }
            }
            assertThat(hits / 100_000.0).isCloseTo(0.25, TOLERANCE);
        }

        @Test
        void nextBooleanHandlesCertainty() {
            Rng rng = Rng.of(11L, 0L, RngStream.TRIGGER_STOCHASTIC);
            assertThat(rng.nextBoolean(0.0)).isFalse();
            assertThat(rng.nextBoolean(1.0)).isTrue();
        }

        @Test
        void gaussianHasRequestedMeanAndSpread() {
            Rng rng = Rng.of(13L, 0L, RngStream.MEMORY_NOISE);
            double sum = 0;
            double sumSq = 0;
            int n = 100_000;
            for (int i = 0; i < n; i++) {
                double value = rng.nextGaussian(2.0, 0.5);
                sum += value;
                sumSq += value * value;
            }
            double mean = sum / n;
            double variance = sumSq / n - mean * mean;
            assertThat(mean).isCloseTo(2.0, TOLERANCE);
            assertThat(Math.sqrt(variance)).isCloseTo(0.5, TOLERANCE);
        }

        @Test
        void poissonMeanMatchesLambda() {
            Rng rng = Rng.of(17L, 0L, RngStream.EVENT_GENERATION);
            long total = 0;
            int n = 50_000;
            for (int i = 0; i < n; i++) {
                total += rng.nextPoisson(2.5);
            }
            assertThat(total / (double) n).isCloseTo(2.5, Offset.offset(0.05));
        }

        @Test
        void poissonHandlesLargeLambdaAndZero() {
            Rng rng = Rng.of(17L, 0L, RngStream.EVENT_GENERATION);
            assertThat(rng.nextPoisson(0.0)).isZero();

            long total = 0;
            for (int i = 0; i < 20_000; i++) {
                total += rng.nextPoisson(50.0);
            }
            assertThat(total / 20_000.0).isCloseTo(50.0, Offset.offset(0.5));
        }

        @Test
        void exponentialMeanIsInverseRate() {
            Rng rng = Rng.of(19L, 0L, RngStream.EVENT_GENERATION);
            double total = 0;
            int n = 50_000;
            for (int i = 0; i < n; i++) {
                total += rng.nextExponential(0.4);
            }
            assertThat(total / n).isCloseTo(2.5, Offset.offset(0.05));
            assertThat(rng.nextExponential(0.0)).isInfinite();
        }
    }

    @Nested
    @DisplayName("collection helpers")
    class CollectionHelpers {

        @Test
        void sampleReturnsDistinctItems() {
            Rng rng = Rng.of(23L, 0L, RngStream.INTERACTION_SELECTION);
            List<String> pool = List.of("a", "b", "c", "d", "e");

            assertThat(rng.sample(pool, 3)).hasSize(3).doesNotHaveDuplicates().isSubsetOf(pool);
        }

        @Test
        void sampleCapsAtPoolSize() {
            Rng rng = Rng.of(23L, 0L, RngStream.INTERACTION_SELECTION);
            assertThat(rng.sample(List.of("a", "b"), 10)).hasSize(2);
        }

        @Test
        void shuffledIsAPermutation() {
            Rng rng = Rng.of(29L, 0L, RngStream.INTERACTION_SELECTION);
            List<Integer> pool = IntStream.range(0, 20).boxed().toList();

            assertThat(rng.shuffled(pool)).containsExactlyInAnyOrderElementsOf(pool);
        }

        @Test
        void pickRejectsEmptyList() {
            Rng rng = Rng.of(29L, 0L, RngStream.INTERACTION_SELECTION);
            assertThatThrownBy(() -> rng.pick(List.<String>of())).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void pickSpreadsAcrossItems() {
            Rng rng = Rng.of(31L, 0L, RngStream.INTERACTION_SELECTION);
            List<String> pool = List.of("a", "b", "c");

            assertThat(IntStream.range(0, 300).mapToObj(i -> rng.pick(pool)).distinct().toList())
                    .containsExactlyInAnyOrderElementsOf(pool);
        }
    }

    @Nested
    @DisplayName("mixing")
    class Mixing {

        @Test
        void seedIsOrderSensitiveAndStable() {
            assertThat(SplitMix64.seed(1, 2, 3)).isEqualTo(SplitMix64.seed(1, 2, 3));
            assertThat(SplitMix64.seed(1, 2, 3)).isNotEqualTo(SplitMix64.seed(3, 2, 1));
        }

        @Test
        void mixAvalanchesNeighbouringInputs() {
            long a = SplitMix64.mix(1L);
            long b = SplitMix64.mix(2L);

            assertThat(Long.bitCount(a ^ b)).isBetween(16, 48);
        }

        @Test
        void streamIdsAreDistinct() {
            assertThat(java.util.Arrays.stream(RngStream.values()).map(RngStream::id).distinct().count())
                    .isEqualTo(RngStream.values().length);
        }
    }
}
