package dev.fsp.engine.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fsp.engine.event.GeneratorState;
import dev.fsp.engine.expr.EvalContext.MemoryAggregateQuery;
import dev.fsp.engine.expr.EvalContext.MemoryAggregateQuery.Aggregate;
import dev.fsp.engine.expr.EvalException;
import dev.fsp.engine.expr.Scope;
import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.memory.MemoryRecord;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SimulationStateTest {

    private static final Offset<Double> PRECISE = Offset.offset(1e-9);

    private SimulationState state;

    @BeforeEach
    void setUp() {
        state = new SimulationState(99L);
        state.addObject(new ObjectState("alice", "person").withVariable("trust", 0.5).withTag("lead")
                .withFeature("remote", 1.0));
        state.addObject(new ObjectState("bob", "person").withVariable("trust", 0.3));
        state.addObject(new ObjectState("team", "group").withVariable("cohesion", 0.6));
        state.setGlobal("tension", 0.2);
    }

    @Nested
    @DisplayName("objects")
    class Objects {

        @Test
        void findsObjectsByIdAndType() {
            assertThat(state.object("alice").get("trust")).isCloseTo(0.5, PRECISE);
            assertThat(state.hasObject("ghost")).isFalse();
            assertThat(state.objectsOfType("person")).extracting(ObjectState::id).containsExactly("alice", "bob");
            assertThatThrownBy(() -> state.object("ghost")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void skipsInactiveObjectsWithoutForgettingThem() {
            state.object("bob").deactivate();

            assertThat(state.activeObjects()).extracting(ObjectState::id).containsExactly("alice", "team");
            assertThat(state.allObjects()).hasSize(3);
            assertThat(state.objectsOfType("person")).extracting(ObjectState::id).containsExactly("alice");
        }

        @Test
        void removingAnObjectAlsoRemovesMemoriesInvolvingIt() {
            state.memories().add(MemoryRecord.builder(1L, "alice", "bob", 0L, DecayModel.NoDecay.INSTANCE).build());

            state.removeObject("bob");

            assertThat(state.hasObject("bob")).isFalse();
            assertThat(state.memories().size()).isZero();
        }

        @Test
        void reportsMissingVariablesClearly() {
            assertThatThrownBy(() -> state.object("alice").get("charisma"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("charisma");
            assertThat(state.object("alice").getOrDefault("charisma", 0.25)).isCloseTo(0.25, PRECISE);
            assertThat(state.object("alice").has("trust")).isTrue();
        }

        @Test
        void describesItselfForDebugging() {
            assertThat(state.object("alice").toString()).contains("alice", "person", "trust");
        }
    }

    @Nested
    @DisplayName("globals, generators and delay lines")
    class OtherState {

        @Test
        void readsAndWritesGlobals() {
            assertThat(state.global("tension")).isCloseTo(0.2, PRECISE);
            state.setGlobal("tension", 0.9);
            assertThat(state.global("tension")).isCloseTo(0.9, PRECISE);
            assertThatThrownBy(() -> state.global("ghost")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void generatorStateDefaultsToInitial() {
            assertThat(state.generatorState("unseen")).isEqualTo(GeneratorState.INITIAL);

            state.setGeneratorState("event", GeneratorState.INITIAL.recording(5L, 2));

            assertThat(state.generatorState("event").totalOccurrences()).isEqualTo(2);
            assertThat(state.generatorStates()).containsKey("event");
        }

        @Test
        void generatorStateIgnoresNonPositiveRecordings() {
            assertThat(GeneratorState.INITIAL.recording(5L, 0)).isEqualTo(GeneratorState.INITIAL);
            assertThat(GeneratorState.INITIAL.ticksSinceLastOccurrence(5L)).isEqualTo(Long.MAX_VALUE);
            assertThat(GeneratorState.startingIn("calm").markovState()).isEqualTo("calm");
        }

        @Test
        void delayLinesAreCreatedOnceAndReused() {
            DelayLine first = state.delayLine("link", 2);
            DelayLine again = state.delayLine("link", 5);

            assertThat(again).isSameAs(first);
            assertThat(again.delayTicks()).isEqualTo(2);
            assertThat(state.delayLines()).containsKey("link");
        }

        @Test
        void ticksAdvanceAndCanBeRestored() {
            state.advanceTick();
            state.advanceTick();
            assertThat(state.tick()).isEqualTo(2L);

            state.restoreTick(100L);
            assertThat(state.tick()).isEqualTo(100L);
        }
    }

    @Nested
    @DisplayName("expression binding")
    class Binding {

        @Test
        void readsVariablesTagsAndTypesThroughTheBoundContext() {
            BoundEvalContext context = BoundEvalContext.of(state, state.object("alice"), state.object("bob"));

            assertThat(context.variable(Scope.SELF, "trust")).isCloseTo(0.5, PRECISE);
            assertThat(context.variable(Scope.TARGET, "trust")).isCloseTo(0.3, PRECISE);
            assertThat(context.variable(Scope.GLOBAL, "tension")).isCloseTo(0.2, PRECISE);
            assertThat(context.tags(Scope.SELF)).containsExactly("lead");
            assertThat(context.tags(Scope.GLOBAL)).isEmpty();
            assertThat(context.typeId(Scope.TARGET)).isEqualTo("person");
            assertThat(context.typeId(Scope.GLOBAL)).isNull();
            assertThat(context.self().id()).isEqualTo("alice");
            assertThat(context.target().id()).isEqualTo("bob");
            assertThat(context.tick()).isZero();
        }

        @Test
        void complainsAboutUnboundScopesRatherThanReturningZero() {
            BoundEvalContext soloContext = BoundEvalContext.forObject(state, state.object("alice"));

            assertThatThrownBy(() -> soloContext.variable(Scope.TARGET, "trust")).isInstanceOf(EvalException.class)
                    .hasMessageContaining("TARGET");
            assertThat(soloContext.tags(Scope.TARGET)).isEmpty();
            assertThat(soloContext.typeId(Scope.TARGET)).isNull();
        }

        @Test
        void complainsAboutUnknownVariables() {
            BoundEvalContext context = BoundEvalContext.globalOnly(state);

            assertThatThrownBy(() -> context.variable(Scope.GLOBAL, "ghost")).isInstanceOf(EvalException.class);
            assertThatThrownBy(() -> BoundEvalContext.forObject(state, state.object("alice"))
                    .variable(Scope.SELF, "ghost")).isInstanceOf(EvalException.class).hasMessageContaining("ghost");
        }

        @Test
        void aggregatesMemoriesForTheBoundObject() {
            state.memories().add(MemoryRecord.builder(1L, "alice", "bob", 0L, DecayModel.NoDecay.INSTANCE)
                    .initialStrength(0.8).build());
            state.memories().add(MemoryRecord.builder(2L, "alice", "team", 0L, DecayModel.NoDecay.INSTANCE)
                    .initialStrength(0.4).build());
            BoundEvalContext context = BoundEvalContext.of(state, state.object("alice"), state.object("bob"));

            assertThat(context.memoryAggregate(new MemoryAggregateQuery(Aggregate.COUNT, null, false, 0.0)))
                    .isCloseTo(2.0, PRECISE);
            assertThat(context.memoryAggregate(new MemoryAggregateQuery(Aggregate.COUNT, null, true, 0.0)))
                    .isCloseTo(1.0, PRECISE);
        }

        @Test
        void memoryAggregatesNeedTheObjectsTheyReferTo() {
            BoundEvalContext globalContext = BoundEvalContext.globalOnly(state);
            MemoryAggregateQuery aboutTarget = new MemoryAggregateQuery(Aggregate.COUNT, null, true, 0.0);

            assertThatThrownBy(() -> globalContext.memoryAggregate(aboutTarget)).isInstanceOf(EvalException.class)
                    .hasMessageContaining("SELF");
            assertThatThrownBy(() -> BoundEvalContext.forObject(state, state.object("alice"))
                    .memoryAggregate(aboutTarget)).isInstanceOf(EvalException.class).hasMessageContaining("TARGET");
        }
    }

    @Nested
    @DisplayName("copying and digests")
    class CopyingAndDigests {

        @Test
        void copiesAreFullyIndependent() {
            state.delayLine("link", 1).advance(0.5);
            SimulationState copy = state.copy();

            state.object("alice").set("trust", 0.99);
            state.setGlobal("tension", 0.99);
            state.advanceTick();

            assertThat(copy.object("alice").get("trust")).isCloseTo(0.5, PRECISE);
            assertThat(copy.global("tension")).isCloseTo(0.2, PRECISE);
            assertThat(copy.tick()).isZero();
            assertThat(copy.runSeed()).isEqualTo(99L);
            assertThat(copy.delayLines()).containsKey("link");
        }

        @Test
        void digestsMatchForEqualStatesAndDifferForChangedOnes() {
            String before = StateDigest.of(state);

            assertThat(StateDigest.of(state.copy())).isEqualTo(before);

            state.object("alice").set("trust", 0.51);
            assertThat(StateDigest.of(state)).isNotEqualTo(before);
        }

        @Test
        void digestsIncludeMemoriesAndTags() {
            state.memories().add(MemoryRecord.builder(1L, "alice", "bob", 0L, DecayModel.NoDecay.INSTANCE).build());

            assertThat(StateDigest.of(state)).contains("alice>bob").contains("lead").contains("tick=0");
        }
    }
}
