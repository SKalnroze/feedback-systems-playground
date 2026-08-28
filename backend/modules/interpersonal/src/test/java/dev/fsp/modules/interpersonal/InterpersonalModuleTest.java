package dev.fsp.modules.interpersonal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.engine.Engine;
import dev.fsp.engine.TickReport;
import dev.fsp.engine.memory.MemoryRecord;
import dev.fsp.engine.module.SimulationModule;
import dev.fsp.engine.module.SimulationModule.Preset;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.state.SimulationState;
import dev.fsp.engine.state.StateDigest;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InterpersonalModuleTest {

    private static final long SEED = 8_675_309L;
    private static final InterpersonalModule MODULE = new InterpersonalModule();

    private static Engine engineFor(SystemSpec spec) {
        return new Engine(spec, MODULE.interactionRules());
    }

    private static SimulationState run(SystemSpec spec, long ticks) {
        Engine engine = engineFor(spec);
        SimulationState state = engine.createInitialState(SEED);
        for (long i = 0; i < ticks; i++) {
            engine.tick(state);
        }
        return state;
    }

    @Nested
    @DisplayName("module registration")
    class Registration {

        @Test
        void isDiscoverableOnTheClasspath() {
            List<SimulationModule> modules = ServiceLoader.load(SimulationModule.class).stream()
                    .map(ServiceLoader.Provider::get).toList();

            assertThat(modules).extracting(SimulationModule::id).contains(InterpersonalModule.ID);
        }

        @Test
        void describesWhatItOffers() {
            assertThat(MODULE.label()).isNotBlank();
            assertThat(MODULE.description()).isNotBlank();
            assertThat(MODULE.objectTypes()).extracting(dev.fsp.engine.spec.ObjectTypeSpec::id)
                    .containsExactly(Person.TYPE_ID);
            assertThat(MODULE.interactionRules()).extracting(dev.fsp.engine.module.InteractionRule::id)
                    .containsExactlyInAnyOrder(MeetRule.ID, ConflictRule.ID, FavourRule.ID, GossipRule.ID);
            assertThat(MODULE.eventTemplates()).isEmpty();
        }

        @Test
        void everyRuleDocumentsItsParameters() {
            assertThat(MODULE.interactionRules()).allSatisfy(rule -> {
                assertThat(rule.label()).isNotBlank();
                assertThat(rule.description()).isNotBlank();
                assertThat(rule.parameters()).isNotEmpty();
                assertThat(rule.parameters()).allSatisfy(parameter -> assertThat(parameter.fallback())
                        .isBetween(parameter.min(), parameter.max()));
            });
        }
    }

    @Nested
    @DisplayName("presets")
    class Presets {

        @Test
        void everyPresetIsValid() {
            assertThat(MODULE.presets()).isNotEmpty();
            assertThat(MODULE.presets()).allSatisfy(preset -> assertThat(preset.spec().validate())
                    .withFailMessage("preset %s: %s", preset.id(), preset.spec().validate()).isEmpty());
        }

        @Test
        void everyPresetRunsWithoutBlowingUp() {
            for (Preset preset : MODULE.presets()) {
                SimulationState state = run(preset.spec(), 60);

                assertThat(state.tick()).isEqualTo(60L);
                assertThat(state.activeObjects()).isNotEmpty();
            }
        }

        @Test
        void everyPresetOnlyUsesRulesTheModuleProvides() {
            List<String> available = MODULE.interactionRules().stream()
                    .map(dev.fsp.engine.module.InteractionRule::id).toList();

            assertThat(MODULE.presets()).allSatisfy(preset -> assertThat(preset.spec().interactions())
                    .allSatisfy(config -> assertThat(config.ruleId()).isIn(available)));
        }
    }

    @Nested
    @DisplayName("people remember each other")
    class Remembering {

        @Test
        void meetingLaysDownMemoriesOnBothSides() {
            SimulationState state = run(InterpersonalPresets.friendGroupDrift().spec(), 20);

            assertThat(state.memories().size()).isPositive();
            assertThat(state.memories().owners()).hasSizeGreaterThan(1);
            state.memories().forEach(memory -> assertThat(memory.ownerId()).isNotEqualTo(memory.subjectId()));
        }

        @Test
        void withoutRehearsalOldMemoriesFadeBelowRetrieval() {
            SystemSpec spec = InterpersonalPresets.friendGroupDrift().spec();
            Engine engine = engineFor(spec);
            SimulationState state = engine.createInitialState(SEED);

            for (int i = 0; i < 20; i++) {
                engine.tick(state);
            }
            long earlyMemories = state.memories().size();

            for (int i = 0; i < 400; i++) {
                engine.tick(state);
            }

            // Pruning is on for this preset, so faded memories are actually discarded.
            assertThat(state.memories().forgottenCount()).isGreaterThan(earlyMemories);
        }

        @Test
        void aFloorAndReactivationKeepFamilyMemoriesAlive() {
            SystemSpec spec = InterpersonalPresets.familyUnitWithGrudges().spec();
            Engine engine = engineFor(spec);
            SimulationState state = engine.createInitialState(SEED);

            MemoryRecord firstMemory = null;
            for (int i = 0; i < 400; i++) {
                engine.tick(state);
                if (firstMemory == null && state.memories().size() > 0) {
                    firstMemory = state.memories().of(state.memories().owners().getFirst()).getFirst();
                }
            }

            assertThat(firstMemory).isNotNull();
            assertThat(firstMemory.strengthAt(state.tick())).isGreaterThanOrEqualTo(0.08);
        }

        @Test
        void gatheringsShowUpAsReactivations() {
            SystemSpec spec = InterpersonalPresets.familyUnitWithGrudges().spec();
            Engine engine = engineFor(spec);
            SimulationState state = engine.createInitialState(SEED);

            int reactivationsAtGatherings = 0;
            for (int i = 0; i < 130; i++) {
                TickReport report = engine.tick(state);
                if (report.events().stream().anyMatch(event -> event.eventId().equals("family-gathering"))) {
                    reactivationsAtGatherings += report.reactivations().size();
                }
            }

            assertThat(reactivationsAtGatherings).isPositive();
        }
    }

    @Nested
    @DisplayName("second-hand impressions")
    class Gossip {

        @Test
        void hearsayReachesPeopleWhoWereNotThere() {
            SystemSpec spec = InterpersonalPresets.officeTeam().spec();
            SimulationState state = run(spec, 200);

            boolean anyHearsay = false;
            for (String owner : state.memories().owners()) {
                for (MemoryRecord memory : state.memories().of(owner)) {
                    if (Person.HEARSAY.equals(memory.kind())) {
                        anyHearsay = true;
                        assertThat(memory.subjectId()).isNotEqualTo(owner);
                    }
                }
            }

            assertThat(anyHearsay).isTrue();
        }

        @Test
        void bystandersFormObservationMemoriesOfConflicts() {
            SimulationState state = run(InterpersonalPresets.officeTeam().spec(), 300);

            boolean anyObservation = false;
            for (String owner : state.memories().owners()) {
                for (MemoryRecord memory : state.memories().of(owner)) {
                    if (Person.OBSERVATION.equals(memory.kind())) {
                        anyObservation = true;
                    }
                }
            }

            assertThat(anyObservation).isTrue();
        }
    }

    @Nested
    @DisplayName("group dynamics")
    class GroupDynamics {

        @Test
        void variablesStayInsideTheirDeclaredBounds() {
            SimulationState state = run(InterpersonalPresets.officeTeam().spec(), 400);

            state.activeObjects().forEach(person -> {
                assertThat(person.getOrDefault(Person.TRUST, 0.0)).isBetween(0.0, 1.0);
                assertThat(person.getOrDefault(Person.RESENTMENT, 0.0)).isBetween(0.0, 1.0);
                assertThat(person.getOrDefault(Person.AFFINITY, 0.0)).isBetween(-1.0, 1.0);
                assertThat(person.getOrDefault(Person.ENERGY, 0.0)).isBetween(0.0, 1.0);
            });
        }

        @Test
        void familiarityOnlyEverGrows() {
            SystemSpec spec = InterpersonalPresets.newArrivalIntegration().spec();
            Engine engine = engineFor(spec);
            SimulationState state = engine.createInitialState(SEED);

            double previous = state.object("sam").get(Person.FAMILIARITY);
            for (int i = 0; i < 200; i++) {
                engine.tick(state);
                double current = state.object("sam").get(Person.FAMILIARITY);
                assertThat(current).isGreaterThanOrEqualTo(previous - 1e-9);
                previous = current;
            }

            assertThat(previous).isGreaterThan(0.0);
        }

        @Test
        void aWorkloadShockRaisesTeamTension() {
            SystemSpec spec = InterpersonalPresets.officeTeam().spec();
            Engine engine = engineFor(spec);
            SimulationState state = engine.createInitialState(SEED);

            for (int i = 0; i < 119; i++) {
                engine.tick(state);
            }
            double workloadBefore = state.global("workload");

            for (int i = 0; i < 30; i++) {
                engine.tick(state);
            }

            assertThat(workloadBefore).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(state.global("workload")).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        void teamTensionTracksAverageResentment() {
            SimulationState state = run(InterpersonalPresets.officeTeam().spec(), 250);

            double meanResentment = state.objectsOfType(Person.TYPE_ID).stream()
                    .mapToDouble(person -> person.getOrDefault(Person.RESENTMENT, 0.0)).average().orElse(0.0);

            assertThat(state.global("teamTension")).isCloseTo(meanResentment,
                    org.assertj.core.data.Offset.offset(0.05));
        }
    }

    @Nested
    @DisplayName("reproducibility")
    class Reproducibility {

        @Test
        void thesePresetsReplayExactly() {
            for (Preset preset : MODULE.presets()) {
                String first = StateDigest.of(run(preset.spec(), 120));
                String second = StateDigest.of(run(preset.spec(), 120));

                assertThat(first).withFailMessage("preset %s did not replay identically", preset.id())
                        .isEqualTo(second);
            }
        }

        @Test
        void aCheckpointResumesWhereItLeftOff() {
            SystemSpec spec = InterpersonalPresets.officeTeam().spec();

            Engine straightEngine = engineFor(spec);
            SimulationState straight = straightEngine.createInitialState(SEED);
            for (int i = 0; i < 150; i++) {
                straightEngine.tick(straight);
            }

            Engine forkEngine = engineFor(spec);
            SimulationState toCheckpoint = forkEngine.createInitialState(SEED);
            for (int i = 0; i < 75; i++) {
                forkEngine.tick(toCheckpoint);
            }
            SimulationState resumed = toCheckpoint.copy();
            forkEngine.graph().primeRateBaseline(resumed);
            for (int i = 0; i < 75; i++) {
                forkEngine.tick(resumed);
            }

            assertThat(StateDigest.of(resumed)).isEqualTo(StateDigest.of(straight));
        }
    }
}
