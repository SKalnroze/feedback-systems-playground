package dev.fsp.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.engine.event.Effect;
import dev.fsp.engine.event.EventGenerator;
import dev.fsp.engine.event.TargetSelector;
import dev.fsp.engine.expr.NumExpr;
import dev.fsp.engine.expr.Predicate;
import dev.fsp.engine.expr.Scope;
import dev.fsp.engine.memory.MemoryRecord;
import dev.fsp.engine.memory.MemoryTrigger;
import dev.fsp.engine.memory.MemoryTrigger.MemoryFilter;
import dev.fsp.engine.memory.MemoryTrigger.ReactivationEffect;
import dev.fsp.engine.memory.TriggerCondition;
import dev.fsp.engine.spec.EventSpec;
import dev.fsp.engine.spec.ObjectSpec;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.spec.VariableSpec;
import dev.fsp.engine.state.SimulationState;
import dev.fsp.engine.state.StateDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EngineTest {

    private static final long SEED = 4242L;
    private static final Offset<Double> PRECISE = Offset.offset(1e-9);

    private static Engine engine(SystemSpec spec) {
        return new Engine(spec, List.of());
    }

    private static SimulationState run(Engine engine, long ticks) {
        SimulationState state = engine.createInitialState(SEED);
        for (long i = 0; i < ticks; i++) {
            engine.tick(state);
        }
        return state;
    }

    @Nested
    @DisplayName("initial state")
    class InitialState {

        @Test
        void startsAtTickZeroWithTypeDefaults() {
            SimulationState state = engine(TestSystems.pair()).createInitialState(SEED);

            assertThat(state.tick()).isZero();
            assertThat(state.runSeed()).isEqualTo(SEED);
            assertThat(state.activeObjects()).extracting(dev.fsp.engine.state.ObjectState::id)
                    .containsExactly("alice", "bob");
            assertThat(state.object("alice").get("trust")).isCloseTo(0.5, PRECISE);
            assertThat(state.memories().size()).isZero();
        }

        @Test
        void appliesPerObjectOverridesAndClampsThemToBounds() {
            SystemSpec spec = SystemSpec.builder("overrides").objectType(TestSystems.personType())
                    .object(ObjectSpec.of("alice", "person").with("trust", 0.9))
                    .object(ObjectSpec.of("bob", "person").with("trust", 5.0)).build();

            SimulationState state = engine(spec).createInitialState(SEED);

            assertThat(state.object("alice").get("trust")).isCloseTo(0.9, PRECISE);
            assertThat(state.object("bob").get("trust")).isCloseTo(1.0, PRECISE);
        }

        @Test
        void seedsGlobalVariables() {
            SystemSpec spec = SystemSpec.builder("globals").objectType(TestSystems.personType())
                    .globalVariable(VariableSpec.unitStock("tension", 0.3)).build();

            assertThat(engine(spec).createInitialState(SEED).global("tension")).isCloseTo(0.3, PRECISE);
        }

        @Test
        void mergesTypeAndObjectTagsAndFeatures() {
            SystemSpec spec = SystemSpec.builder("tags")
                    .objectType(new dev.fsp.engine.spec.ObjectTypeSpec("person", "Person",
                            List.of(VariableSpec.unitStock("trust", 0.5)), TestSystems.MEMORY,
                            java.util.Set.of("human"), Map.of("baseline", 1.0)))
                    .object(ObjectSpec.of("alice", "person").tagged("newcomer").featured("remote", 1.0)).build();

            var alice = engine(spec).createInitialState(SEED).object("alice");

            assertThat(alice.tags()).containsExactlyInAnyOrder("human", "newcomer");
            assertThat(alice.features()).containsKeys("baseline", "remote");
        }
    }

    @Nested
    @DisplayName("ticking")
    class Ticking {

        @Test
        void advancesTheTickCounter() {
            Engine engine = engine(TestSystems.pair());
            SimulationState state = engine.createInitialState(SEED);

            assertThat(engine.tick(state).tick()).isEqualTo(1L);
            assertThat(engine.tick(state).tick()).isEqualTo(2L);
            assertThat(state.tick()).isEqualTo(2L);
        }

        @Test
        void aSystemWithNothingInItStaysQuiet() {
            Engine engine = engine(TestSystems.pair());
            SimulationState state = engine.createInitialState(SEED);

            TickReport report = engine.tick(state);

            assertThat(report.isQuiet()).isTrue();
            assertThat(report.events()).isEmpty();
            assertThat(report.memoriesCreated()).isZero();
        }
    }

    @Nested
    @DisplayName("events")
    class Events {

        @Test
        void firesOnScheduleAndRecordsWhoItHitAndWhatTheyRemember() {
            Engine engine = engine(TestSystems.scheduledShock(3L));
            SimulationState state = engine.createInitialState(SEED);

            List<TickReport> reports = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                reports.add(engine.tick(state));
            }

            assertThat(reports.get(2).events()).singleElement()
                    .satisfies(occurrence -> assertThat(occurrence.eventId()).isEqualTo("argument"));
            assertThat(reports.get(0).events()).isEmpty();
            assertThat(state.memories().size()).isEqualTo(1);

            MemoryRecord memory = state.memories().of(state.memories().owners().getFirst()).getFirst();
            assertThat(memory.kind()).isEqualTo("interaction");
            assertThat(memory.valence()).isCloseTo(-0.8, PRECISE);
            assertThat(memory.createdTick()).isEqualTo(3L);
        }

        @Test
        void appliesEffectsToTheTargetItSelected() {
            Engine engine = engine(TestSystems.scheduledShock(1L));
            SimulationState state = engine.createInitialState(SEED);

            TickReport report = engine.tick(state);
            String hit = report.events().getFirst().targetIds().getFirst();

            assertThat(state.object(hit).get("trust")).isCloseTo(0.3, PRECISE);
        }

        @Test
        void respectsAConditionGate() {
            SystemSpec spec = SystemSpec.builder("gated").objectType(TestSystems.personType())
                    .object(ObjectSpec.of("alice", "person").with("trust", 0.1))
                    .object(ObjectSpec.of("bob", "person").with("trust", 0.9))
                    .event(EventSpec
                            .of("only-trusting", EventGenerator.Periodic.every(1L), TargetSelector.Everyone.all(),
                                    List.of(Effect.AdjustVariable.add(Scope.SELF, "resentment", 0.1)))
                            .onlyWhen(new Predicate.Compare(Predicate.Compare.Op.GT, NumExpr.self("trust"),
                                    NumExpr.of(0.5))))
                    .build();
            Engine engine = engine(spec);
            SimulationState state = engine.createInitialState(SEED);

            engine.tick(state);

            assertThat(state.object("alice").get("resentment")).isZero();
            assertThat(state.object("bob").get("resentment")).isCloseTo(0.1, PRECISE);
        }

        @Test
        void honoursCooldownBetweenOccurrences() {
            SystemSpec spec = SystemSpec.builder("cooldown").objectType(TestSystems.personType())
                    .object(ObjectSpec.of("alice", "person"))
                    .event(EventSpec.of("every-tick", EventGenerator.Periodic.every(1L), TargetSelector.Everyone.all(),
                            List.of(Effect.AdjustVariable.add(Scope.SELF, "resentment", 0.1))).withCooldown(3))
                    .build();
            Engine engine = engine(spec);

            SimulationState state = run(engine, 9);

            // Ticks 1, 4 and 7 fire; the two ticks after each are suppressed.
            assertThat(state.object("alice").get("resentment")).isCloseTo(0.3, Offset.offset(1e-6));
        }

        @Test
        void stopsAfterTheOccurrenceLimit() {
            SystemSpec spec = SystemSpec.builder("limited").objectType(TestSystems.personType())
                    .object(ObjectSpec.of("alice", "person"))
                    .event(EventSpec.of("twice", EventGenerator.Periodic.every(1L), TargetSelector.Everyone.all(),
                            List.of(Effect.AdjustVariable.add(Scope.SELF, "resentment", 0.1))).limitedTo(2))
                    .build();

            SimulationState state = run(engine(spec), 20);

            assertThat(state.object("alice").get("resentment")).isCloseTo(0.2, Offset.offset(1e-6));
        }

        @Test
        void oneEventCanSetOffAnother() {
            SystemSpec spec = SystemSpec.builder("cascade").objectType(TestSystems.personType())
                    .object(ObjectSpec.of("alice", "person"))
                    .event(EventSpec.of("first", EventGenerator.FixedSchedule.at(1L), TargetSelector.Everyone.all(),
                            List.of(new Effect.EmitEvent("second"))))
                    .event(EventSpec.of("second", EventGenerator.Never.INSTANCE, TargetSelector.Everyone.all(),
                            List.of(Effect.AdjustVariable.add(Scope.SELF, "resentment", 0.4))))
                    .build();
            Engine engine = engine(spec);
            SimulationState state = engine.createInitialState(SEED);

            TickReport report = engine.tick(state);

            assertThat(state.object("alice").get("resentment")).isCloseTo(0.4, PRECISE);
            assertThat(report.events()).extracting(TickReport.EventOccurrence::eventId).containsExactly("first",
                    "second");
            assertThat(report.events().getLast().cascaded()).isTrue();
        }

        @Test
        void cascadesCannotLoopForever() {
            SystemSpec spec = SystemSpec.builder("runaway").objectType(TestSystems.personType())
                    .object(ObjectSpec.of("alice", "person"))
                    .event(EventSpec.of("ping", EventGenerator.FixedSchedule.at(1L), TargetSelector.Everyone.all(),
                            List.of(new Effect.EmitEvent("ping"))))
                    .build();
            Engine engine = engine(spec);
            SimulationState state = engine.createInitialState(SEED);

            TickReport report = engine.tick(state);

            assertThat(report.logLines()).anyMatch(line -> line.contains("cascade truncated"));
        }

        @Test
        void bystandersFormObservationMemoriesOfWhatTheySaw() {
            SystemSpec spec = SystemSpec.builder("observed").objectType(TestSystems.personType())
                    .object(ObjectSpec.of("alice", "person")).object(ObjectSpec.of("bob", "person"))
                    .object(ObjectSpec.of("carol", "person"))
                    .event(EventSpec.of("public-row", EventGenerator.FixedSchedule.at(1L),
                            new TargetSelector.Specific(java.util.Set.of("alice")),
                            List.of(new Effect.ToBystanders("person",
                                    List.of(Effect.InjectMemory.of("observation", 0.4, -0.5)), 1.0))))
                    .build();
            Engine engine = engine(spec);
            SimulationState state = engine.createInitialState(SEED);

            engine.tick(state);

            assertThat(state.memories().of("bob")).hasSize(1);
            assertThat(state.memories().of("carol")).hasSize(1);
            assertThat(state.memories().of("alice")).isEmpty();
            assertThat(state.memories().of("bob").getFirst().subjectId()).isEqualTo("alice");
            assertThat(state.memories().of("bob").getFirst().kind()).isEqualTo("observation");
        }
    }

    @Nested
    @DisplayName("memory reactivation")
    class Reactivation {

        private SystemSpec withTrigger(TriggerCondition condition, ReactivationEffect effect) {
            return SystemSpec.builder("triggered").objectType(TestSystems.personType())
                    .object(ObjectSpec.of("alice", "person")).object(ObjectSpec.of("bob", "person"))
                    .event(EventSpec.of("meet", EventGenerator.FixedSchedule.at(1L),
                            new TargetSelector.Specific(java.util.Set.of("alice")),
                            List.of(new Effect.InjectMemory(Scope.SELF, Scope.SELF, "interaction", NumExpr.of(1.0),
                                    NumExpr.of(-0.5), 0.9, Map.of("conflict", 1.0), null))))
                    .event(EventSpec.of("reminder", EventGenerator.FixedSchedule.at(10L),
                            TargetSelector.GlobalOnly.INSTANCE, List.of()))
                    .trigger(new MemoryTrigger("t1", "trigger", MemoryFilter.ANY, condition, effect, 0)).build();
        }

        @Test
        void anEventCanBringAMemoryBackAndStrengthenIt() {
            Engine engine = engine(withTrigger(new TriggerCondition.OnEvent(java.util.Set.of("reminder")),
                    new ReactivationEffect(0.0, 0.0, true, 0.0, null, 0.0, null)));
            SimulationState state = engine.createInitialState(SEED);

            for (int i = 0; i < 9; i++) {
                engine.tick(state);
            }
            MemoryRecord memory = state.memories().of("alice").getFirst();
            double beforeReminder = memory.strengthAt(state.tick());

            TickReport report = engine.tick(state);

            assertThat(report.reactivations()).singleElement().satisfies(record -> {
                assertThat(record.triggerId()).isEqualTo("t1");
                assertThat(record.ownerId()).isEqualTo("alice");
                assertThat(record.gain()).isPositive();
            });
            assertThat(memory.strengthAt(state.tick())).isGreaterThan(beforeReminder);
            assertThat(memory.reactivationCount()).isEqualTo(1);
        }

        @Test
        void rememberedFeelingsCanSourWithRepeatedRumination() {
            Engine engine = engine(withTrigger(new TriggerCondition.Periodic(2L, 0L),
                    new ReactivationEffect(0.0, 0.0, false, -0.1, null, 0.0, null)));
            SimulationState state = engine.createInitialState(SEED);

            for (int i = 0; i < 9; i++) {
                engine.tick(state);
            }

            MemoryRecord memory = state.memories().of("alice").getFirst();
            assertThat(memory.valence()).isLessThan(-0.5).isGreaterThanOrEqualTo(-1.0);
        }

        @Test
        void recallCanItselfBeRemembered() {
            Engine engine = engine(withTrigger(new TriggerCondition.OnEvent(java.util.Set.of("reminder")),
                    new ReactivationEffect(0.0, 0.0, false, 0.0, "rumination", 0.5, null)));
            SimulationState state = engine.createInitialState(SEED);

            for (int i = 0; i < 10; i++) {
                engine.tick(state);
            }

            assertThat(state.memories().of("alice")).extracting(MemoryRecord::kind).contains("rumination");
        }

        @Test
        void aCueSimilarEnoughToTheMemoryBringsItBack() {
            SystemSpec spec = SystemSpec.builder("cued").objectType(TestSystems.personType())
                    .object(ObjectSpec.of("alice", "person"))
                    .event(EventSpec.of("first-row", EventGenerator.FixedSchedule.at(1L),
                            TargetSelector.Everyone.all(),
                            List.of(new Effect.InjectMemory(Scope.SELF, Scope.SELF, "interaction", NumExpr.of(1.0),
                                    NumExpr.of(-0.7), 0.9, Map.of("conflict", 1.0, "public", 1.0), null))))
                    .event(EventSpec.of("later-row", EventGenerator.FixedSchedule.at(6L),
                            TargetSelector.Everyone.all(),
                            List.of(new Effect.InjectMemory(Scope.SELF, Scope.SELF, "interaction", NumExpr.of(0.5),
                                    NumExpr.of(-0.4), 0.5, Map.of("conflict", 1.0, "public", 1.0), null))))
                    .trigger(new MemoryTrigger("cue", "cue", new MemoryFilter(java.util.Set.of(), 0.0, 1.0, 2L),
                            new TriggerCondition.CueSimilarity(0.8), ReactivationEffect.REHEARSE, 0))
                    .build();
            Engine engine = engine(spec);
            SimulationState state = engine.createInitialState(SEED);

            for (int i = 0; i < 6; i++) {
                engine.tick(state);
            }

            MemoryRecord original = state.memories().of("alice").getFirst();
            assertThat(original.reactivationCount()).isEqualTo(1);
        }

        @Test
        void theSameCueDoesNotReviveUnrelatedMemories() {
            SystemSpec spec = SystemSpec.builder("unrelated").objectType(TestSystems.personType())
                    .object(ObjectSpec.of("alice", "person"))
                    .event(EventSpec.of("holiday", EventGenerator.FixedSchedule.at(1L), TargetSelector.Everyone.all(),
                            List.of(new Effect.InjectMemory(Scope.SELF, Scope.SELF, "interaction", NumExpr.of(1.0),
                                    NumExpr.of(0.8), 0.9, Map.of("holiday", 1.0), null))))
                    .event(EventSpec.of("row", EventGenerator.FixedSchedule.at(6L), TargetSelector.Everyone.all(),
                            List.of(new Effect.InjectMemory(Scope.SELF, Scope.SELF, "interaction", NumExpr.of(0.5),
                                    NumExpr.of(-0.4), 0.5, Map.of("conflict", 1.0), null))))
                    .trigger(new MemoryTrigger("cue", "cue", new MemoryFilter(java.util.Set.of(), 0.0, 1.0, 2L),
                            new TriggerCondition.CueSimilarity(0.8), ReactivationEffect.REHEARSE, 0))
                    .build();
            Engine engine = engine(spec);

            SimulationState state = run(engine, 6);

            assertThat(state.memories().of("alice").getFirst().reactivationCount()).isZero();
        }

        @Test
        void perTickLimitStopsOneEventRehearsingEverything() {
            SystemSpec spec = SystemSpec.builder("limited-trigger").objectType(TestSystems.personType())
                    .object(ObjectSpec.of("alice", "person"))
                    .event(EventSpec.of("drip", EventGenerator.Periodic.every(1L), TargetSelector.Everyone.all(),
                            List.of(new Effect.InjectMemory(Scope.SELF, Scope.SELF, "interaction", NumExpr.of(1.0),
                                    NumExpr.of(0.0), 0.5, Map.of(), null))))
                    .trigger(new MemoryTrigger("t", "t", MemoryFilter.ANY, new TriggerCondition.Periodic(1L, 0L),
                            ReactivationEffect.REHEARSE, 1))
                    .build();
            Engine engine = engine(spec);
            SimulationState state = engine.createInitialState(SEED);

            TickReport last = null;
            for (int i = 0; i < 5; i++) {
                last = engine.tick(state);
            }

            assertThat(state.memories().of("alice")).hasSize(5);
            assertThat(last.reactivations()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("variable graph")
    class Graph {

        @Test
        void aReinforcingLoopGrowsWithoutBound() {
            Engine engine = engine(TestSystems.loopSystem(0));
            SimulationState state = engine.createInitialState(SEED);
            state.object("alice").set("resentment", 0.1);

            run(engine, 0);
            for (int i = 0; i < 40; i++) {
                engine.tick(state);
            }

            assertThat(state.object("alice").get("withdrawal")).isGreaterThan(0.1);
            assertThat(state.object("alice").get("resentment")).isGreaterThan(0.1);
        }

        @Test
        void aNegativeLinkPushesItsTargetDown() {
            Engine engine = engine(TestSystems.loopSystem(0));
            SimulationState state = engine.createInitialState(SEED);
            state.object("alice").set("withdrawal", 0.5);

            engine.tick(state);

            assertThat(state.object("alice").get("trust")).isCloseTo(0.4, PRECISE);
        }

        @Test
        void delayedLinksArriveLate() {
            Engine engine = engine(TestSystems.loopSystem(3));
            SimulationState state = engine.createInitialState(SEED);
            state.object("alice").set("withdrawal", 0.5);

            engine.tick(state);
            assertThat(state.object("alice").get("trust")).isCloseTo(0.5, PRECISE);
            engine.tick(state);
            engine.tick(state);
            assertThat(state.object("alice").get("trust")).isCloseTo(0.5, PRECISE);
            engine.tick(state);
            assertThat(state.object("alice").get("trust")).isLessThan(0.5);
        }

        @Test
        void variablesStayInsideTheirDeclaredBounds() {
            Engine engine = engine(TestSystems.loopSystem(0));
            SimulationState state = engine.createInitialState(SEED);
            state.object("alice").set("resentment", 0.9);

            for (int i = 0; i < 200; i++) {
                engine.tick(state);
            }

            assertThat(state.object("alice").get("withdrawal")).isBetween(0.0, 1.0);
            assertThat(state.object("alice").get("trust")).isBetween(0.0, 1.0);
        }

        @Test
        void reportsWhatEachLinkDelivered() {
            Engine engine = engine(TestSystems.loopSystem(0));
            SimulationState state = engine.createInitialState(SEED);
            state.object("alice").set("resentment", 0.5);

            TickReport report = engine.tick(state);

            assertThat(report.linkContributions()).containsKey("alice.withdrawal");
            assertThat(report.linkContributions().get("alice.withdrawal")).isCloseTo(0.05, PRECISE);
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        void twoRunsOfTheSameSeedAreIdentical() {
            SystemSpec spec = TestSystems.scheduledShock(2L, 5L, 9L);

            String first = StateDigest.of(run(engine(spec), 20));
            String second = StateDigest.of(run(engine(spec), 20));

            assertThat(first).isEqualTo(second);
        }

        @Test
        void differentSeedsDiverge() {
            SystemSpec spec = SystemSpec.builder("random").objectType(TestSystems.personType())
                    .object(ObjectSpec.of("alice", "person")).object(ObjectSpec.of("bob", "person"))
                    .object(ObjectSpec.of("carol", "person"))
                    .event(EventSpec.of("chance", new EventGenerator.Bernoulli(0.5),
                            new TargetSelector.RandomK("person", 1),
                            List.of(Effect.AdjustVariable.add(Scope.SELF, "resentment", 0.05))))
                    .build();
            Engine engine = engine(spec);

            SimulationState a = engine.createInitialState(1L);
            SimulationState b = engine.createInitialState(2L);
            for (int i = 0; i < 50; i++) {
                engine.tick(a);
                engine.tick(b);
            }

            assertThat(StateDigest.of(a)).isNotEqualTo(StateDigest.of(b));
        }

        @Test
        void restoringACheckpointContinuesTheSameRun() {
            SystemSpec spec = TestSystems.scheduledShock(3L, 8L, 15L);
            Engine engine = engine(spec);

            SimulationState straight = engine.createInitialState(SEED);
            for (int i = 0; i < 20; i++) {
                engine.tick(straight);
            }

            Engine forkEngine = engine(spec);
            SimulationState toCheckpoint = forkEngine.createInitialState(SEED);
            for (int i = 0; i < 10; i++) {
                forkEngine.tick(toCheckpoint);
            }
            SimulationState resumed = toCheckpoint.copy();
            forkEngine.graph().primeRateBaseline(resumed);
            for (int i = 0; i < 10; i++) {
                forkEngine.tick(resumed);
            }

            assertThat(StateDigest.of(resumed)).isEqualTo(StateDigest.of(straight));
        }

        @Test
        void aForkedRunDoesNotDisturbTheOriginal() {
            Engine engine = engine(TestSystems.scheduledShock(3L, 8L));
            SimulationState original = engine.createInitialState(SEED);
            for (int i = 0; i < 5; i++) {
                engine.tick(original);
            }

            SimulationState fork = original.copy();
            String originalDigest = StateDigest.of(original);
            for (int i = 0; i < 10; i++) {
                engine.tick(fork);
            }

            assertThat(StateDigest.of(original)).isEqualTo(originalDigest);
            assertThat(StateDigest.of(fork)).isNotEqualTo(originalDigest);
        }
    }
}
