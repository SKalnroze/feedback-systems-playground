package dev.fsp.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fsp.engine.expr.EvalContext;
import dev.fsp.engine.expr.NumExpr;
import dev.fsp.engine.expr.Scope;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;
import dev.fsp.engine.state.BoundEvalContext;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TriggerConditionTest {

    private SimulationState state;
    private MemoryRecord memory;
    private FakeContext context;

    @BeforeEach
    void setUp() {
        state = new SimulationState(3L);
        state.addObject(new ObjectState("alice", "person").withVariable("resentment", 0.7));
        state.addObject(new ObjectState("bob", "person").withVariable("resentment", 0.1));
        state.restoreTick(10L);
        memory = state.memories()
                .add(MemoryRecord.builder(1L, "alice", "bob", 0L, DecayModel.NoDecay.INSTANCE)
                        .initialStrength(0.6).features(Map.of("conflict", 1.0)).build());
        context = new FakeContext(state);
    }

    @Test
    void onEventFiresOnlyForTheNamedEvents() {
        TriggerCondition condition = new TriggerCondition.OnEvent(Set.of("argument", "layoff"));

        assertThat(condition.matches(context, memory)).isFalse();
        context.firedEventIds.add("argument");
        assertThat(condition.matches(context, memory)).isTrue();
    }

    @Test
    void subjectPresentFiresWhenTheOtherPersonIsAround() {
        TriggerCondition condition = TriggerCondition.SubjectPresent.INSTANCE;

        assertThat(condition.matches(context, memory)).isFalse();
        context.inPlay.add("bob");
        assertThat(condition.matches(context, memory)).isTrue();
    }

    @Test
    void onConditionEvaluatesAnExpressionOverBothParties() {
        TriggerCondition condition = new TriggerCondition.OnCondition(
                new dev.fsp.engine.expr.Predicate.Compare(dev.fsp.engine.expr.Predicate.Compare.Op.GT,
                        NumExpr.self("resentment"), NumExpr.of(0.5)));

        assertThat(condition.matches(context, memory)).isTrue();

        state.object("alice").set("resentment", 0.2);
        assertThat(condition.matches(context, memory)).isFalse();
    }

    @Test
    void cueSimilarityFiresWhenTheStimulusResemblesTheMemory() {
        TriggerCondition condition = new TriggerCondition.CueSimilarity(0.8);

        assertThat(condition.matches(context, memory)).isFalse();
        context.cue.put("conflict", 1.0);
        assertThat(condition.matches(context, memory)).isTrue();
        context.cue.clear();
        context.cue.put("holiday", 1.0);
        assertThat(condition.matches(context, memory)).isFalse();
    }

    @Test
    void periodicFiresOnItsRhythm() {
        assertThat(new TriggerCondition.Periodic(5L, 0L).matches(context, memory)).isTrue();
        assertThat(new TriggerCondition.Periodic(3L, 0L).matches(context, memory)).isFalse();
        assertThat(new TriggerCondition.Periodic(5L, 20L).matches(context, memory)).isFalse();
    }

    @Test
    void stochasticFiresAtRoughlyItsProbability() {
        assertThat(new TriggerCondition.Stochastic(0.0).matches(context, memory)).isFalse();
        assertThat(new TriggerCondition.Stochastic(1.0).matches(context, memory)).isTrue();

        int fired = 0;
        TriggerCondition condition = new TriggerCondition.Stochastic(0.3);
        for (long tick = 0; tick < 2_000; tick++) {
            state.restoreTick(tick);
            if (condition.matches(context, memory)) {
                fired++;
            }
        }
        assertThat(fired / 2_000.0).isCloseTo(0.3, org.assertj.core.data.Offset.offset(0.03));
    }

    @Test
    void strengthBandFiresOnlyInsideItsRange() {
        assertThat(new TriggerCondition.StrengthBand(0.0, 0.5).matches(context, memory)).isFalse();
        assertThat(new TriggerCondition.StrengthBand(0.5, 1.0).matches(context, memory)).isTrue();
    }

    @Test
    void conditionsCombine() {
        TriggerCondition yes = new TriggerCondition.Stochastic(1.0);
        TriggerCondition no = new TriggerCondition.Stochastic(0.0);

        assertThat(new TriggerCondition.All(List.of(yes, yes)).matches(context, memory)).isTrue();
        assertThat(new TriggerCondition.All(List.of(yes, no)).matches(context, memory)).isFalse();
        assertThat(new TriggerCondition.All(List.of()).matches(context, memory)).isTrue();
        assertThat(new TriggerCondition.Any(List.of(no, yes)).matches(context, memory)).isTrue();
        assertThat(new TriggerCondition.Any(List.of(no, no)).matches(context, memory)).isFalse();
        assertThat(new TriggerCondition.Any(List.of()).matches(context, memory)).isFalse();
        assertThat(new TriggerCondition.Not(no).matches(context, memory)).isTrue();
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new TriggerCondition.CueSimilarity(1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TriggerCondition.Periodic(0L, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TriggerCondition.Stochastic(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TriggerCondition.StrengthBand(1.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyConditionReportsADistinctKind() {
        List<TriggerCondition> conditions = List.of(new TriggerCondition.OnEvent(Set.of("e")),
                TriggerCondition.SubjectPresent.INSTANCE,
                new TriggerCondition.OnCondition(dev.fsp.engine.expr.Predicate.always()),
                new TriggerCondition.CueSimilarity(0.5), new TriggerCondition.Periodic(1L, 0L),
                new TriggerCondition.Stochastic(0.5), new TriggerCondition.StrengthBand(0.0, 1.0),
                new TriggerCondition.All(List.of()), new TriggerCondition.Any(List.of()),
                new TriggerCondition.Not(TriggerCondition.SubjectPresent.INSTANCE));

        assertThat(conditions.stream().map(TriggerCondition::kind).distinct().count()).isEqualTo(conditions.size());
    }

    @Test
    void triggerAndFilterValidateThemselves() {
        assertThatThrownBy(() -> new MemoryTrigger(" ", "t", MemoryTrigger.MemoryFilter.ANY,
                TriggerCondition.SubjectPresent.INSTANCE, MemoryTrigger.ReactivationEffect.REHEARSE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryTrigger("t", "t", MemoryTrigger.MemoryFilter.ANY,
                TriggerCondition.SubjectPresent.INSTANCE, MemoryTrigger.ReactivationEffect.REHEARSE, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryTrigger("t", "t", null, TriggerCondition.SubjectPresent.INSTANCE,
                MemoryTrigger.ReactivationEffect.REHEARSE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryTrigger.MemoryFilter(Set.of(), 1.0, 0.0, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryTrigger.MemoryFilter(Set.of(), 0.0, 1.0, -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryTrigger.ReactivationEffect(-0.1, 0.0, false, 0.0, null, 0.0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryTrigger.ReactivationEffect(0.0, -0.1, false, 0.0, null, 0.0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryTrigger.ReactivationEffect(0.0, 0.0, false, 0.0, null, 2.0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filtersNarrowByKindStrengthAndAge() {
        assertThat(MemoryTrigger.MemoryFilter.ANY.accepts(memory, 10L)).isTrue();
        assertThat(MemoryTrigger.MemoryFilter.ofKinds("observation").accepts(memory, 10L)).isFalse();
        assertThat(MemoryTrigger.MemoryFilter.ofKinds("interaction").accepts(memory, 10L)).isTrue();
        assertThat(new MemoryTrigger.MemoryFilter(Set.of(), 0.8, 1.0, 0L).accepts(memory, 10L)).isFalse();
        assertThat(new MemoryTrigger.MemoryFilter(Set.of(), 0.0, 1.0, 20L).accepts(memory, 10L)).isFalse();
    }

    @Test
    void triggersExposeTheirLimitsAndEffects() {
        MemoryTrigger trigger = new MemoryTrigger("t", "Trigger", MemoryTrigger.MemoryFilter.ANY,
                TriggerCondition.SubjectPresent.INSTANCE,
                new MemoryTrigger.ReactivationEffect(0.1, 1.0, true, -0.05, "rumination", 0.5, "aftermath"), 3);

        assertThat(trigger.hasPerTickLimit()).isTrue();
        assertThat(trigger.effect().spawnsDerivedMemory()).isTrue();
        assertThat(trigger.effect().emitsEvent()).isTrue();
        assertThat(trigger.effect().toReactivation(9L, "t").triggerId()).isEqualTo("t");
        assertThat(MemoryTrigger.ReactivationEffect.REHEARSE.spawnsDerivedMemory()).isFalse();
        assertThat(MemoryTrigger.ReactivationEffect.REHEARSE.emitsEvent()).isFalse();
    }

    /** Minimal {@link TriggerContext} whose inputs the test drives directly. */
    private static final class FakeContext implements TriggerContext {

        private final SimulationState state;
        private final Set<String> firedEventIds = new LinkedHashSet<>();
        private final Set<String> inPlay = new LinkedHashSet<>();
        private final Map<String, Double> cue = new LinkedHashMap<>();

        private FakeContext(SimulationState state) {
            this.state = state;
        }

        @Override
        public long tick() {
            return state.tick();
        }

        @Override
        public Set<String> firedEventIds() {
            return firedEventIds;
        }

        @Override
        public Set<String> objectsInPlay() {
            return inPlay;
        }

        @Override
        public Map<String, Double> cue() {
            return cue;
        }

        @Override
        public EvalContext evalContextFor(MemoryRecord memory) {
            return BoundEvalContext.of(state, state.object(memory.ownerId()), state.object(memory.subjectId()));
        }

        @Override
        public Rng rngFor(MemoryRecord memory) {
            return Rng.of(state.runSeed(), state.tick(), RngStream.TRIGGER_STOCHASTIC, memory.id());
        }
    }

    @Test
    void scopesCoverSelfTargetAndGlobal() {
        assertThat(Scope.values()).containsExactly(Scope.SELF, Scope.TARGET, Scope.GLOBAL);
    }
}
