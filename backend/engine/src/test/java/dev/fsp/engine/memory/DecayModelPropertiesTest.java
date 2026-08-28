package dev.fsp.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Invariants that must hold for every decay model, whatever its parameters. These are the rules a
 * new model has to obey to be admissible, so they are asserted generically rather than per model.
 */
class DecayModelPropertiesTest {

    @Property(tries = 500)
    void strengthStaysWithinUnitInterval(@ForAll("models") DecayModel model,
            @ForAll @DoubleRange(min = 0.01, max = 1.0) double initialStrength,
            @ForAll @LongRange(min = 0L, max = 100_000L) long tick) {

        assertThat(model.strengthAt(TestTrace.fresh(initialStrength), tick)).isBetween(0.0, 1.0);
    }

    @Property(tries = 500)
    void unrehearsedMemoriesNeverGrowStronger(@ForAll("models") DecayModel model,
            @ForAll @DoubleRange(min = 0.01, max = 1.0) double initialStrength,
            @ForAll @LongRange(min = 0L, max = 5_000L) long earlier,
            @ForAll @LongRange(min = 0L, max = 5_000L) long later) {

        long from = Math.min(earlier, later);
        long to = Math.max(earlier, later);
        TestTrace trace = TestTrace.fresh(initialStrength);

        assertThat(model.strengthAt(trace, to)).isLessThanOrEqualTo(model.strengthAt(trace, from) + 1e-12);
    }

    @Property(tries = 500)
    void strengthNeverFallsBelowTheFloor(@ForAll("models") DecayModel model,
            @ForAll @DoubleRange(min = 0.0, max = 0.9) double floor,
            @ForAll @LongRange(min = 0L, max = 100_000L) long tick) {

        DecayModel withFloor = withCommon(model, new DecayCommon(floor, 0.0));

        assertThat(withFloor.strengthAt(TestTrace.fresh(1.0), tick)).isGreaterThanOrEqualTo(floor - 1e-12);
    }

    @Property(tries = 300)
    void evaluationIsPure(@ForAll("models") DecayModel model,
            @ForAll @DoubleRange(min = 0.01, max = 1.0) double initialStrength,
            @ForAll @LongRange(min = 0L, max = 10_000L) long tick) {

        TestTrace trace = TestTrace.fresh(initialStrength).reactivated(tick / 2, 0.05, 1.0, false);

        assertThat(model.strengthAt(trace, tick)).isEqualTo(model.strengthAt(trace, tick));
    }

    @Property(tries = 300)
    void reactivationNeverWeakensAMemory(@ForAll("models") DecayModel model,
            @ForAll @DoubleRange(min = 0.01, max = 0.8) double initialStrength,
            @ForAll @LongRange(min = 1L, max = 500L) long reactivationTick,
            @ForAll @LongRange(min = 500L, max = 5_000L) long observedAt) {

        TestTrace plain = TestTrace.fresh(initialStrength);
        TestTrace rehearsed = plain.reactivated(reactivationTick, 0.1, 1.0, true);

        assertThat(model.strengthAt(rehearsed, observedAt))
                .isGreaterThanOrEqualTo(model.strengthAt(plain, observedAt) - 1e-12);
    }

    @Property(tries = 300)
    void interferenceOnlyEverSuppresses(@ForAll("models") DecayModel model,
            @ForAll @DoubleRange(min = 0.0, max = 2.0) double interference,
            @ForAll @LongRange(min = 0L, max = 1_000L) long tick, @ForAll @LongRange(min = 1L, max = 20L) long similar) {

        DecayModel interfering = withCommon(model, new DecayCommon(0.0, interference));
        TestTrace trace = TestTrace.fresh(1.0);

        assertThat(interfering.strengthAt(trace.withSimilar((int) similar), tick))
                .isLessThanOrEqualTo(interfering.strengthAt(trace, tick) + 1e-12);
    }

    @Provide
    Arbitrary<DecayModel> models() {
        return Arbitraries.of(NoDecay.INSTANCE, Exponential.ofHalfLife(3.0), Exponential.ofHalfLife(250.0),
                PowerLaw.ofExponent(0.3), PowerLaw.ofExponent(1.4), new Ebbinghaus(15.0, 4.0, DecayCommon.NONE),
                new Linear(0.002, DecayCommon.NONE), new Linear(0.05, DecayCommon.NONE),
                new Logistic(0.4, 40.0, DecayCommon.NONE),
                new StepThreshold(
                        List.of(new StepThreshold.Step(20L, 0.7), new StepThreshold.Step(80L, 0.25),
                                new StepThreshold.Step(400L, 0.05)),
                        DecayCommon.NONE),
                ActRBaseLevel.standard(), new ActRBaseLevel(0.8, -0.5, 0.2, DecayCommon.NONE));
    }

    /** Rebuilds a model with different shared parameters, so properties can vary floor/interference. */
    private static DecayModel withCommon(DecayModel model, DecayCommon common) {
        return switch (model) {
            case NoDecay ignored -> new NoDecay(common);
            case Exponential m -> new Exponential(m.halfLife(), common);
            case PowerLaw m -> new PowerLaw(m.exponent(), common);
            case Ebbinghaus m -> new Ebbinghaus(m.baseStability(), m.stabilityGrowth(), common);
            case Linear m -> new Linear(m.ratePerTick(), common);
            case Logistic m -> new Logistic(m.steepness(), m.midpoint(), common);
            case StepThreshold m -> new StepThreshold(m.steps(), common);
            case ActRBaseLevel m -> new ActRBaseLevel(m.decayExponent(), m.threshold(), m.noiseScale(), common);
        };
    }
}
