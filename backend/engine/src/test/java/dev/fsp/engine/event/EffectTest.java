package dev.fsp.engine.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fsp.engine.event.TargetSelector.Target;
import dev.fsp.engine.expr.NumExpr;
import dev.fsp.engine.expr.Predicate;
import dev.fsp.engine.expr.Scope;
import dev.fsp.engine.memory.MemoryRecord;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EffectTest {

    private static final Offset<Double> PRECISE = Offset.offset(1e-9);

    private SimulationState state;
    private Target pair;
    private RecordingEffectContext context;

    @BeforeEach
    void setUp() {
        state = new SimulationState(11L);
        state.addObject(new ObjectState("alice", "person").withVariable("trust", 0.5).withVariable("resentment", 0.1));
        state.addObject(new ObjectState("bob", "person").withVariable("trust", 0.4).withVariable("resentment", 0.0));
        state.addObject(new ObjectState("carol", "person").withVariable("trust", 0.6).withVariable("resentment", 0.0));
        state.setGlobal("tension", 0.2);
        pair = new Target(state.object("alice"), state.object("bob"));
        context = new RecordingEffectContext(state, pair);
    }

    @Test
    void setVariableOverwritesTheValue() {
        new Effect.SetVariable(Scope.SELF, "trust", NumExpr.of(0.9)).apply(context, pair);

        assertThat(state.object("alice").get("trust")).isCloseTo(0.9, PRECISE);
    }

    @Test
    void setVariableCanWriteToTheTargetAndToGlobals() {
        new Effect.SetVariable(Scope.TARGET, "trust", NumExpr.of(0.1)).apply(context, pair);
        new Effect.SetVariable(Scope.GLOBAL, "tension", NumExpr.of(0.7)).apply(context, pair);

        assertThat(state.object("bob").get("trust")).isCloseTo(0.1, PRECISE);
        assertThat(state.global("tension")).isCloseTo(0.7, PRECISE);
    }

    @Test
    void adjustVariableAddsAndMultiplies() {
        Effect.AdjustVariable.add(Scope.SELF, "trust", -0.2).apply(context, pair);
        assertThat(state.object("alice").get("trust")).isCloseTo(0.3, PRECISE);

        new Effect.AdjustVariable(Scope.SELF, "trust", NumExpr.of(2.0), Effect.AdjustVariable.Mode.MULTIPLY)
                .apply(context, pair);
        assertThat(state.object("alice").get("trust")).isCloseTo(0.6, PRECISE);
    }

    @Test
    void adjustmentsCanBeComputedFromCurrentState() {
        Effect effect = new Effect.AdjustVariable(Scope.SELF, "resentment",
                new NumExpr.Arithmetic(NumExpr.Arithmetic.Op.MULTIPLY, NumExpr.target("trust"), NumExpr.of(0.5)),
                Effect.AdjustVariable.Mode.ADD);

        effect.apply(context, pair);

        assertThat(state.object("alice").get("resentment")).isCloseTo(0.1 + 0.4 * 0.5, PRECISE);
    }

    @Test
    void injectMemoryRecordsWhoRemembersWhatAboutWhom() {
        Effect.InjectMemory.of("interaction", 0.8, -0.6).apply(context, pair);

        assertThat(context.memories).singleElement().satisfies(memory -> {
            assertThat(memory.ownerId()).isEqualTo("alice");
            assertThat(memory.subjectId()).isEqualTo("bob");
            assertThat(memory.kind()).isEqualTo("interaction");
            assertThat(memory.initialStrength()).isCloseTo(0.8, PRECISE);
            assertThat(memory.valence()).isCloseTo(-0.6, PRECISE);
        });
    }

    @Test
    void injectMemoryDoesNothingWithoutBothParties() {
        Effect.InjectMemory.of("interaction", 0.8, -0.6).apply(context, Target.of(state.object("alice")));

        assertThat(context.memories).isEmpty();
    }

    @Test
    void injectMemoryRejectsABlankKind() {
        assertThatThrownBy(() -> new Effect.InjectMemory(Scope.SELF, Scope.TARGET, " ", NumExpr.of(1.0),
                NumExpr.of(0.0), 0.5, Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tagsCanBeAddedAndRemoved() {
        new Effect.AddTag(Scope.SELF, "shaken").apply(context, pair);
        assertThat(state.object("alice").tags()).contains("shaken");

        new Effect.RemoveTag(Scope.SELF, "shaken").apply(context, pair);
        assertThat(state.object("alice").tags()).doesNotContain("shaken");
    }

    @Test
    void emitEventQueuesTheFollowUp() {
        new Effect.EmitEvent("aftermath").apply(context, pair);

        assertThat(context.emittedEvents).containsExactly("aftermath");
    }

    @Test
    void spawnObjectAddsSomeoneNew() {
        new Effect.SpawnObject("person", Map.of("trust", 0.75)).apply(context, pair);

        assertThat(state.allObjects()).hasSize(4);
        assertThat(context.logLines).anyMatch(line -> line.startsWith("spawned"));
    }

    @Test
    void removeObjectTakesTheirMemoriesWithThem() {
        Effect.InjectMemory.of("interaction", 0.8, 0.0).apply(context, pair);
        assertThat(state.memories().size()).isEqualTo(1);

        new Effect.RemoveObject(Scope.SELF).apply(context, pair);

        assertThat(state.hasObject("alice")).isFalse();
        assertThat(state.memories().size()).isZero();
    }

    @Test
    void conditionalRunsOnlyTheBranchThatApplies() {
        Effect effect = new Effect.Conditional(
                new Predicate.Compare(Predicate.Compare.Op.GT, NumExpr.self("trust"), NumExpr.of(0.4)),
                List.of(Effect.AdjustVariable.add(Scope.SELF, "resentment", 0.1)),
                List.of(Effect.AdjustVariable.add(Scope.SELF, "resentment", 0.9)));

        effect.apply(context, pair);

        assertThat(state.object("alice").get("resentment")).isCloseTo(0.2, PRECISE);
    }

    @Test
    void bystandersAreAffectedButThePartiesAreNot() {
        Effect effect = new Effect.ToBystanders("person", List.of(Effect.InjectMemory.of("observation", 0.3, -0.4)),
                1.0);

        effect.apply(context, pair);

        assertThat(context.memories).singleElement().satisfies(memory -> {
            assertThat(memory.ownerId()).isEqualTo("carol");
            assertThat(memory.subjectId()).isEqualTo("alice");
            assertThat(memory.kind()).isEqualTo("observation");
        });
    }

    @Test
    void bystandersNoticeOnlySometimesWhenProbabilityIsBelowOne() {
        state.addObject(new ObjectState("dave", "person"));
        state.addObject(new ObjectState("erin", "person"));
        Effect effect = new Effect.ToBystanders("person", List.of(Effect.InjectMemory.of("observation", 0.3, -0.4)),
                0.0);

        effect.apply(context, pair);

        assertThat(context.memories).isEmpty();
    }

    @Test
    void bystanderProbabilityMustBeAProbability() {
        assertThatThrownBy(() -> new Effect.ToBystanders(null, List.of(), 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writingToAnAbsentScopeIsANoOpRatherThanACrash() {
        Target soloTarget = Target.of(state.object("alice"));

        new Effect.SetVariable(Scope.TARGET, "trust", NumExpr.of(0.1)).apply(context, soloTarget);
        new Effect.AddTag(Scope.TARGET, "x").apply(context, soloTarget);

        assertThat(state.object("bob").get("trust")).isCloseTo(0.4, PRECISE);
    }

    @Test
    void featuresHelperBuildsAUnitWeightedVector() {
        assertThat(Effect.features("conflict", "public")).containsEntry("conflict", 1.0).containsEntry("public", 1.0);
    }

    @Test
    void everyEffectReportsADistinctKind() {
        List<Effect> effects = List.of(new Effect.SetVariable(Scope.SELF, "trust", NumExpr.of(0.0)),
                Effect.AdjustVariable.add(Scope.SELF, "trust", 0.0), Effect.InjectMemory.of("k", 0.1, 0.0),
                new Effect.AddTag(Scope.SELF, "t"), new Effect.RemoveTag(Scope.SELF, "t"),
                new Effect.EmitEvent("e"), new Effect.SpawnObject("person", Map.of()),
                new Effect.RemoveObject(Scope.SELF),
                new Effect.Conditional(Predicate.always(), List.of(), List.of()),
                new Effect.ToBystanders(null, List.of(), 1.0));

        assertThat(effects.stream().map(Effect::kind).distinct().count()).isEqualTo(effects.size());
    }

    @Test
    void memoriesRecordTheEventThatCausedThem() {
        Effect.InjectMemory.of("interaction", 0.8, 0.0).apply(context, pair);

        MemoryRecord memory = context.memories.getFirst();
        assertThat(memory.sourceEventId()).isNull();
        assertThat(memory.toString()).contains("alice->bob");
    }
}
