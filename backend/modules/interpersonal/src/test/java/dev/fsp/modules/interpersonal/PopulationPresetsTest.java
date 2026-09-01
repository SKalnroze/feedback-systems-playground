package dev.fsp.modules.interpersonal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.engine.Engine;
import dev.fsp.engine.module.SimulationModule.Preset;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.state.SimulationState;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The population presets, checked against what their descriptions promise.
 *
 * <p>A preset whose description says "watch this number move" and whose number does not move is
 * worse than no preset, because the reader concludes the tool is broken rather than that the
 * example is. So each of these asserts the claim the description makes, not merely that the spec
 * loads.
 */
class PopulationPresetsTest {

    private static SimulationState runFor(Preset preset, long seed, int ticks) {
        Engine engine = new Engine(preset.spec(), new InterpersonalModule().interactionRules());
        SimulationState state = engine.createInitialState(seed);
        for (int tick = 0; tick < ticks; tick++) {
            engine.tick(state);
        }
        return state;
    }

    @Test
    @DisplayName("every population preset is valid and runs")
    void allPresetsRun() {
        for (Preset preset : PopulationPresets.all()) {
            List<SystemSpec.ValidationIssue> errors = preset.spec().validate().stream()
                    .filter(SystemSpec.ValidationIssue::isError).toList();
            assertThat(errors).as("errors in %s", preset.id()).isEmpty();

            SimulationState state = runFor(preset, 1234L, 60);
            assertThat(state.tick()).isEqualTo(60);
            assertThat(state.allObjects()).as("%s has a population", preset.id()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("the rumour reaches people who were never told directly")
    void theRumourSpreadsBeyondTheTwelve() {
        SimulationState state = runFor(PopulationPresets.rumourMill(), 99L, 160);

        // Twelve were told. If more than twelve are carrying resentment, it travelled by gossip,
        // which is the mechanism the preset exists to show.
        long carrying = state.membersOf("townsfolk").stream()
                .filter(member -> member.get(Person.RESENTMENT) > 0.02)
                .count();
        assertThat(carrying).as("people holding the story").isGreaterThan(12L);
        assertThat(state.global("commonKnowledge")).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("the covering loop pushes workload above the shock that started it")
    void burnoutCompounds() {
        SystemSpec spec = PopulationPresets.burnoutContagion().spec();
        SimulationState state = runFor(PopulationPresets.burnoutContagion(), 7L, 300);

        double startingWorkload = spec.globalVariable("workload").initial();
        // The point of the preset: load ends higher than it began, because exhaustion feeds back
        // into it rather than merely responding to it.
        assertThat(state.global("workload")).as("workload after three quarters")
                .isGreaterThan(startingWorkload);

        double meanEnergy = state.membersOf("team").stream()
                .mapToDouble(member -> member.get(Person.ENERGY)).average().orElseThrow();
        assertThat(meanEnergy).as("the team is more tired than it started").isLessThan(0.8);
    }

    @Test
    @DisplayName("losing one connector out of two hundred and six is visible in the group")
    void oneDepartureMovesTheWholeGroup() {
        Preset preset = PopulationPresets.theFewWhoHoldItTogether();

        Engine engine = new Engine(preset.spec(), new InterpersonalModule().interactionRules());
        SimulationState state = engine.createInitialState(2024L);
        for (int tick = 0; tick < 149; tick++) {
            engine.tick(state);
        }
        double before = meanEngagement(state);

        for (int tick = 0; tick < 120; tick++) {
            engine.tick(state);
        }
        double after = meanEngagement(state);

        // Not asserting a direction of the group's mood - that is what the reader is meant to
        // discover - only that one member out of 206 leaving is not lost in the noise.
        assertThat(Math.abs(after - before)).as("the departure registers in the group's engagement")
                .isGreaterThan(0.001);
    }

    private static double meanEngagement(SimulationState state) {
        return state.membersOf("members").stream()
                .mapToDouble(member -> member.get(Person.ENGAGEMENT)).average().orElseThrow();
    }
}
