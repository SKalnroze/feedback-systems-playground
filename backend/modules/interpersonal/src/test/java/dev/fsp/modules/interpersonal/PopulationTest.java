package dev.fsp.modules.interpersonal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.engine.Engine;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.spec.ObjectSpec;
import dev.fsp.engine.spec.SimulationSettings;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Groups driven through a real module rather than a synthetic two-variable spec.
 *
 * <p>A coupling test proves the arithmetic; this proves the thing an author actually wants, which
 * is that two hundred people who have never been listed individually still meet each other, form
 * memories, and diverge.
 */
class PopulationTest {

    private static final long SEED = 31_337L;
    private static final int MEMBERS = 200;

    private static SystemSpec crowd() {
        return SystemSpec.builder("crowd").name("A crowd").module(InterpersonalModule.ID)
                .objectType(Person.type())
                .object(new ObjectSpec("person", Person.TYPE_ID, "person", Map.of(), Set.of(), Map.of(), MEMBERS))
                .interaction(InteractionRule.Config.enabled(MeetRule.ID).with("encountersPerTick", 8.0))
                .interaction(InteractionRule.Config.enabled(ConflictRule.ID))
                .settings(new SimulationSettings(1, 0, 0L, 1, false)).build();
    }

    private static SimulationState runFor(int ticks) {
        Engine engine = new Engine(crowd(), new InterpersonalModule().interactionRules());
        SimulationState state = engine.createInitialState(SEED);
        for (int tick = 0; tick < ticks; tick++) {
            engine.tick(state);
        }
        return state;
    }

    @Test
    @DisplayName("one authored object becomes a population that interacts")
    void aGroupInteractsLikeIndividuals() {
        SimulationState state = runFor(120);

        assertThat(state.membersOf("person")).hasSize(MEMBERS);
        assertThat(state.memories().size()).as("the crowd remembers its encounters").isPositive();
    }

    @Test
    @DisplayName("members diverge, which is the reason to simulate a population at all")
    void membersDivergeFromEachOther() {
        SimulationState state = runFor(120);

        List<Double> trust = state.membersOf("person").stream()
                .map(member -> member.get(Person.TRUST))
                .toList();
        double min = trust.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double max = trust.stream().mapToDouble(Double::doubleValue).max().orElseThrow();

        assertThat(max - min)
                .as("if every member held the same value, a group would be no better than one object")
                .isGreaterThan(0.01);
        assertThat(trust).allSatisfy(value -> assertThat(value).isBetween(0.0, 1.0));
    }

    @Test
    @DisplayName("a population run is reproducible from its seed like any other")
    void sameSeedSamePopulation() {
        List<Double> first = runFor(60).membersOf("person").stream().map(ObjectState::variables)
                .map(variables -> variables.get(Person.TRUST)).toList();
        List<Double> second = runFor(60).membersOf("person").stream().map(ObjectState::variables)
                .map(variables -> variables.get(Person.TRUST)).toList();

        assertThat(first).isEqualTo(second);
    }
}
