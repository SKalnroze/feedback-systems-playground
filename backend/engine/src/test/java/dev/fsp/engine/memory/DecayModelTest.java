package dev.fsp.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fsp.engine.memory.DecayModel.ActRBaseLevel;
import dev.fsp.engine.memory.DecayModel.DecayCommon;
import dev.fsp.engine.memory.DecayModel.Ebbinghaus;
import dev.fsp.engine.memory.DecayModel.Exponential;
import dev.fsp.engine.memory.DecayModel.Linear;
import dev.fsp.engine.memory.DecayModel.Logistic;
import dev.fsp.engine.memory.DecayModel.NoDecay;
import dev.fsp.engine.memory.DecayModel.PowerLaw;
import dev.fsp.engine.memory.DecayModel.StepThreshold;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DecayModelTest {

    private static final Offset<Double> PRECISE = Offset.offset(1e-9);
    private static final Offset<Double> LOOSE = Offset.offset(1e-3);

    @Nested
    @DisplayName("no decay")
    class None {

        @Test
        void holdsInitialStrengthForever() {
            TestTrace trace = TestTrace.fresh(0.8);

            assertThat(NoDecay.INSTANCE.strengthAt(trace, 0L)).isCloseTo(0.8, PRECISE);
            assertThat(NoDecay.INSTANCE.strengthAt(trace, 1_000_000L)).isCloseTo(0.8, PRECISE);
        }

        @Test
        void reportsItsKind() {
            assertThat(NoDecay.INSTANCE.kind()).isEqualTo("none");
        }
    }

    @Nested
    @DisplayName("exponential")
    class ExponentialDecay {

        @Test
        void halvesEveryHalfLife() {
            DecayModel model = Exponential.ofHalfLife(10.0);
            TestTrace trace = TestTrace.fresh(1.0);

            assertThat(model.strengthAt(trace, 0L)).isCloseTo(1.0, PRECISE);
            assertThat(model.strengthAt(trace, 10L)).isCloseTo(0.5, PRECISE);
            assertThat(model.strengthAt(trace, 20L)).isCloseTo(0.25, PRECISE);
            assertThat(model.strengthAt(trace, 30L)).isCloseTo(0.125, PRECISE);
        }

        @Test
        void scalesWithInitialStrength() {
            DecayModel model = Exponential.ofHalfLife(4.0);

            assertThat(model.strengthAt(TestTrace.fresh(0.6), 4L)).isCloseTo(0.3, PRECISE);
        }

        @Test
        void rejectsNonPositiveHalfLife() {
            assertThatThrownBy(() -> Exponential.ofHalfLife(0.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("halfLife");
        }
    }

    @Nested
    @DisplayName("power law")
    class PowerLawDecay {

        @Test
        void followsWickelgrenCurve() {
            DecayModel model = PowerLaw.ofExponent(0.5);
            TestTrace trace = TestTrace.fresh(1.0);

            assertThat(model.strengthAt(trace, 0L)).isCloseTo(1.0, PRECISE);
            assertThat(model.strengthAt(trace, 3L)).isCloseTo(0.5, PRECISE);
            assertThat(model.strengthAt(trace, 99L)).isCloseTo(0.1, PRECISE);
        }

        @Test
        void keepsALongerTailThanExponential() {
            DecayModel power = PowerLaw.ofExponent(0.5);
            DecayModel exponential = Exponential.ofHalfLife(3.0);
            TestTrace trace = TestTrace.fresh(1.0);

            assertThat(power.strengthAt(trace, 3L)).isCloseTo(exponential.strengthAt(trace, 3L), LOOSE);
            assertThat(power.strengthAt(trace, 200L)).isGreaterThan(exponential.strengthAt(trace, 200L));
        }

        @Test
        void rejectsNonPositiveExponent() {
            assertThatThrownBy(() -> PowerLaw.ofExponent(-1.0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("ebbinghaus with spacing effect")
    class EbbinghausDecay {

        @Test
        void decaysExponentiallyAgainstStability() {
            Ebbinghaus model = new Ebbinghaus(20.0, 5.0, DecayCommon.NONE);
            TestTrace trace = TestTrace.fresh(1.0);

            assertThat(model.strengthAt(trace, 20L)).isCloseTo(Math.exp(-1.0), PRECISE);
        }

        @Test
        void reactivationRaisesStabilityAndSlowsForgetting() {
            Ebbinghaus model = new Ebbinghaus(20.0, 5.0, DecayCommon.NONE);
            TestTrace unrehearsed = TestTrace.fresh(1.0);
            TestTrace rehearsed = unrehearsed.reactivated(10L, 0.0, 2.0, false);

            assertThat(model.stability(unrehearsed)).isCloseTo(20.0, PRECISE);
            assertThat(model.stability(rehearsed)).isCloseTo(30.0, PRECISE);
            assertThat(model.strengthAt(rehearsed, 40L)).isGreaterThan(model.strengthAt(unrehearsed, 40L));
        }

        @Test
        void rejectsInvalidParameters() {
            assertThatThrownBy(() -> new Ebbinghaus(0.0, 1.0, DecayCommon.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Ebbinghaus(1.0, -1.0, DecayCommon.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("linear")
    class LinearDecay {

        @Test
        void losesAFixedAmountPerTick() {
            DecayModel model = new Linear(0.1, DecayCommon.NONE);
            TestTrace trace = TestTrace.fresh(1.0);

            assertThat(model.strengthAt(trace, 3L)).isCloseTo(0.7, PRECISE);
        }

        @Test
        void bottomsOutAtZeroRatherThanGoingNegative() {
            DecayModel model = new Linear(0.1, DecayCommon.NONE);

            assertThat(model.strengthAt(TestTrace.fresh(1.0), 50L)).isZero();
        }

        @Test
        void rejectsNegativeRate() {
            assertThatThrownBy(() -> new Linear(-0.1, DecayCommon.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("logistic")
    class LogisticDecay {

        @Test
        void startsAtFullStrengthAndHalvesAtTheMidpoint() {
            DecayModel model = new Logistic(0.5, 20.0, DecayCommon.NONE);
            TestTrace trace = TestTrace.fresh(1.0);

            assertThat(model.strengthAt(trace, 0L)).isCloseTo(1.0, PRECISE);
            assertThat(model.strengthAt(trace, 20L)).isCloseTo(0.5, LOOSE);
        }

        @Test
        void holdsAPlateauBeforeTheCliff() {
            DecayModel model = new Logistic(0.8, 30.0, DecayCommon.NONE);
            TestTrace trace = TestTrace.fresh(1.0);

            assertThat(model.strengthAt(trace, 10L)).isGreaterThan(0.99);
            assertThat(model.strengthAt(trace, 45L)).isLessThan(0.01);
        }

        @Test
        void rejectsInvalidParameters() {
            assertThatThrownBy(() -> new Logistic(0.0, 10.0, DecayCommon.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Logistic(1.0, 0.0, DecayCommon.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("step threshold")
    class StepThresholdDecay {

        private final DecayModel model = new StepThreshold(
                List.of(new StepThreshold.Step(30L, 0.2), new StepThreshold.Step(10L, 0.6)), DecayCommon.NONE);

        @Test
        void holdsFullStrengthUntilTheFirstStep() {
            assertThat(model.strengthAt(TestTrace.fresh(1.0), 9L)).isCloseTo(1.0, PRECISE);
        }

        @Test
        void dropsToEachRetentionLevelInTurn() {
            TestTrace trace = TestTrace.fresh(1.0);

            assertThat(model.strengthAt(trace, 10L)).isCloseTo(0.6, PRECISE);
            assertThat(model.strengthAt(trace, 29L)).isCloseTo(0.6, PRECISE);
            assertThat(model.strengthAt(trace, 30L)).isCloseTo(0.2, PRECISE);
            assertThat(model.strengthAt(trace, 5_000L)).isCloseTo(0.2, PRECISE);
        }

        @Test
        void rejectsEmptyOrAmbiguousLadders() {
            assertThatThrownBy(() -> new StepThreshold(List.of(), DecayCommon.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StepThreshold(
                    List.of(new StepThreshold.Step(5L, 0.5), new StepThreshold.Step(5L, 0.2)), DecayCommon.NONE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicate step");
            assertThatThrownBy(() -> new StepThreshold.Step(-1L, 0.5))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new StepThreshold.Step(1L, 1.5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("ACT-R base level")
    class ActRDecay {

        @Test
        void fadesWithoutRehearsal() {
            DecayModel model = ActRBaseLevel.standard();
            TestTrace trace = TestTrace.fresh(1.0);

            assertThat(model.strengthAt(trace, 1L)).isGreaterThan(model.strengthAt(trace, 100L));
            assertThat(model.strengthAt(trace, 10_000L)).isLessThan(0.05);
        }

        @Test
        void rehearsalRaisesActivationAboveAnUnrehearsedTrace() {
            DecayModel model = ActRBaseLevel.standard();
            TestTrace plain = TestTrace.fresh(1.0);
            TestTrace rehearsed = plain.reactivatedAt(50L).reactivatedAt(80L);

            assertThat(model.strengthAt(rehearsed, 100L)).isGreaterThan(model.strengthAt(plain, 100L));
        }

        @Test
        void spacedRehearsalBeatsMassedRehearsal() {
            DecayModel model = ActRBaseLevel.standard();
            TestTrace massed = TestTrace.fresh(1.0).reactivatedAt(10L).reactivatedAt(11L).reactivatedAt(12L);
            TestTrace spaced = TestTrace.fresh(1.0).reactivatedAt(10L).reactivatedAt(60L).reactivatedAt(140L);

            assertThat(model.strengthAt(spaced, 400L)).isGreaterThan(model.strengthAt(massed, 400L));
        }

        @Test
        void ignoresReactivationsInTheFuture() {
            DecayModel model = ActRBaseLevel.standard();
            TestTrace withFuture = TestTrace.fresh(1.0).reactivatedAt(500L);

            assertThat(model.strengthAt(withFuture, 100L))
                    .isCloseTo(model.strengthAt(TestTrace.fresh(1.0), 100L), PRECISE);
        }

        @Test
        void rejectsInvalidParameters() {
            assertThatThrownBy(() -> new ActRBaseLevel(0.0, 0.0, 0.4, DecayCommon.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ActRBaseLevel(0.5, 0.0, 0.0, DecayCommon.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("shared behaviour")
    class Shared {

        @Test
        void floorKeepsAPermanentTrace() {
            DecayModel model = new Exponential(1.0, DecayCommon.floor(0.15));

            assertThat(model.strengthAt(TestTrace.fresh(1.0), 10_000L)).isCloseTo(0.15, PRECISE);
        }

        @Test
        void consolidationBoostsRaiseEffectiveInitialStrength() {
            DecayModel model = Exponential.ofHalfLife(10.0);
            TestTrace boosted = TestTrace.fresh(0.4).reactivated(5L, 0.3, 0.0, true);

            assertThat(model.effectiveInitialStrength(boosted)).isCloseTo(0.7, PRECISE);
            assertThat(model.strengthAt(boosted, 15L)).isCloseTo(0.35, PRECISE);
        }

        @Test
        void consolidationCannotExceedFullStrength() {
            DecayModel model = NoDecay.INSTANCE;
            TestTrace boosted = TestTrace.fresh(0.9).reactivated(1L, 0.5, 0.0, false);

            assertThat(model.effectiveInitialStrength(boosted)).isEqualTo(1.0);
        }

        @Test
        void resettingTheClockRestartsDecay() {
            DecayModel model = Exponential.ofHalfLife(10.0);
            TestTrace reset = TestTrace.fresh(1.0).reactivated(100L, 0.0, 0.0, true);

            assertThat(model.strengthAt(reset, 110L)).isCloseTo(0.5, PRECISE);
        }

        @Test
        void interferenceSuppressesRetrievalOfSimilarMemories() {
            DecayModel model = new DecayModel.NoDecay(new DecayCommon(0.0, 0.5));
            TestTrace alone = TestTrace.fresh(1.0);

            assertThat(model.strengthAt(alone, 0L)).isCloseTo(1.0, PRECISE);
            assertThat(model.strengthAt(alone.withSimilar(2), 0L)).isCloseTo(0.5, PRECISE);
        }

        @Test
        void strengthNeverGoesNegativeBeforeTheOriginTick() {
            DecayModel model = Exponential.ofHalfLife(5.0);
            TestTrace future = new TestTrace(1.0, 100L, 100L, List.of(), 0);

            assertThat(model.strengthAt(future, 50L)).isCloseTo(1.0, PRECISE);
        }

        @Test
        void isForgottenComparesAgainstTheRetrievalThreshold() {
            DecayModel model = Exponential.ofHalfLife(5.0);
            TestTrace trace = TestTrace.fresh(1.0);

            assertThat(model.isForgotten(trace, 0L, 0.2)).isFalse();
            assertThat(model.isForgotten(trace, 50L, 0.2)).isTrue();
        }

        @Test
        void everyModelReportsADistinctKind() {
            List<DecayModel> models = List.of(NoDecay.INSTANCE, Exponential.ofHalfLife(1.0), PowerLaw.ofExponent(0.5),
                    new Ebbinghaus(1.0, 0.0, DecayCommon.NONE), new Linear(0.1, DecayCommon.NONE),
                    new Logistic(1.0, 1.0, DecayCommon.NONE),
                    new StepThreshold(List.of(new StepThreshold.Step(1L, 0.5)), DecayCommon.NONE),
                    ActRBaseLevel.standard());

            assertThat(models.stream().map(DecayModel::kind).distinct().count()).isEqualTo(models.size());
        }

        @Test
        void rejectsInvalidCommonParameters() {
            assertThatThrownBy(() -> new DecayCommon(1.5, 0.0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DecayCommon(0.0, -1.0)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void reactivationRejectsNegativeGains() {
            assertThatThrownBy(() -> new Reactivation(1L, -0.1, 0.0, false, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Reactivation(1L, 0.0, -0.1, false, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(Reactivation.at(5L).tick()).isEqualTo(5L);
        }
    }
}
