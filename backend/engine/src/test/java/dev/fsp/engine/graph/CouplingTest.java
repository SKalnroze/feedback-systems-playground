package dev.fsp.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.engine.Engine;
import dev.fsp.engine.memory.MemorySettings;
import dev.fsp.engine.spec.Coupling;
import dev.fsp.engine.spec.LinkSpec;
import dev.fsp.engine.spec.ObjectSpec;
import dev.fsp.engine.spec.ObjectTypeSpec;
import dev.fsp.engine.spec.SimulationSettings;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.spec.VariableRef;
import dev.fsp.engine.spec.VariableSpec;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * What each coupling mode actually does to which members.
 *
 * <p>These assert the arithmetic rather than the shape: a coupling that reached the right objects
 * with the wrong values, or the right values in the wrong order, would be indistinguishable from a
 * correct one in a test that only counted writes.
 *
 * <p>Every system here uses one source variable and one target variable with a gain of one and no
 * transfer, so a member's resulting value is exactly what the coupling delivered to it.
 */
class CouplingTest {

    private static final long SEED = 4242L;

    private static ObjectTypeSpec dial() {
        // AUXILIARY on the target so each tick replaces rather than accumulates, which makes the
        // delivered contribution readable straight off the variable.
        return new ObjectTypeSpec("dial", "Dial",
                List.of(VariableSpec.signedStock("out", 0.0),
                        new VariableSpec("in", "in", VariableSpec.Kind.AUXILIARY, 0.0, -1000.0, 1000.0)),
                MemorySettings.DEFAULT, Set.of(), Map.of());
    }

    private static SystemSpec system(int sourceCount, int targetCount, Coupling coupling) {
        return SystemSpec.builder("coupling").name("coupling").objectType(dial())
                .object(new ObjectSpec("src", "dial", "src", Map.of(), Set.of(), Map.of(), sourceCount))
                .object(new ObjectSpec("dst", "dial", "dst", Map.of(), Set.of(), Map.of(), targetCount))
                .link(new LinkSpec("wire", "wire", new VariableRef.OfObject("src", "out"),
                        new VariableRef.OfObject("dst", "in"), 1.0, 0, Transfer.linear(), false, coupling))
                .settings(new SimulationSettings(1, 0, 0L, 1, false)).build();
    }

    /** Gives each source member a distinct value so the pairing is visible in the result. */
    private static SimulationState primed(SystemSpec spec, double... sourceValues) {
        Engine engine = new Engine(spec, List.of());
        SimulationState state = engine.createInitialState(SEED);
        List<ObjectState> sources = state.membersOf("src");
        for (int index = 0; index < sources.size(); index++) {
            sources.get(index).set("out", sourceValues[Math.min(index, sourceValues.length - 1)]);
        }
        engine.tick(state);
        return state;
    }

    private static List<Double> targetValues(SimulationState state) {
        return state.membersOf("dst").stream().map(member -> member.get("in")).toList();
    }

    @Test
    @DisplayName("one to one pairs member i with member i")
    void oneToOnePairsByPosition() {
        SystemSpec spec = system(3, 3, Coupling.of(Coupling.Mode.ONE_TO_ONE));
        SimulationState state = primed(spec, 1.0, 2.0, 3.0);
        assertThat(targetValues(state)).containsExactly(1.0, 2.0, 3.0);
    }

    @Test
    @DisplayName("one to many sends the single source value to every member")
    void oneToManyBroadcasts() {
        SystemSpec spec = system(1, 4, Coupling.of(Coupling.Mode.ONE_TO_MANY));
        SimulationState state = primed(spec, 7.0);
        assertThat(targetValues(state)).containsExactly(7.0, 7.0, 7.0, 7.0);
    }

    @Test
    @DisplayName("many to one reduces the whole source group into the single target")
    void manyToOneReduces() {
        SystemSpec spec = system(4, 1, Coupling.of(Coupling.Mode.MANY_TO_ONE));
        SimulationState state = primed(spec, 1.0, 2.0, 3.0, 4.0);
        assertThat(targetValues(state)).containsExactly(2.5);
    }

    @Test
    @DisplayName("the aggregate is the author's choice, not always the mean")
    void manyToOneHonoursTheChosenAggregate() {
        SystemSpec spec = system(4, 1,
                Coupling.of(Coupling.Mode.MANY_TO_ONE).reducedBy(VariableRef.OfType.Aggregate.SUM));
        SimulationState state = primed(spec, 1.0, 2.0, 3.0, 4.0);
        assertThat(targetValues(state)).containsExactly(10.0);
    }

    @Test
    @DisplayName("many to many by aggregate gives every target the same reduced value")
    void aggregateReachesEveryTarget() {
        SystemSpec spec = system(4, 3, Coupling.of(Coupling.Mode.MANY_TO_MANY_AGGREGATE));
        SimulationState state = primed(spec, 1.0, 2.0, 3.0, 4.0);
        assertThat(targetValues(state)).containsExactly(2.5, 2.5, 2.5);
    }

    @Test
    @DisplayName("all pairs delivers the sum of every source to every target")
    void allPairsSumsEverySource() {
        SystemSpec spec = system(3, 2, Coupling.of(Coupling.Mode.MANY_TO_MANY_ALL));
        SimulationState state = primed(spec, 1.0, 2.0, 3.0);
        // Each target receives one contribution per source member, and they sum.
        assertThat(targetValues(state)).containsExactly(6.0, 6.0);
    }

    @Test
    @DisplayName("random pairing gives each target one source value, reproducibly")
    void randomPairingIsReproducible() {
        SystemSpec spec = system(4, 5, Coupling.of(Coupling.Mode.MANY_TO_MANY_RANDOM));
        List<Double> first = targetValues(primed(spec, 10.0, 20.0, 30.0, 40.0));
        List<Double> second = targetValues(primed(spec, 10.0, 20.0, 30.0, 40.0));

        assertThat(first).as("same seed, same pairing").isEqualTo(second);
        assertThat(first).as("each target got exactly one source member's value")
                .allMatch(value -> List.of(10.0, 20.0, 30.0, 40.0).contains(value));
    }

    @Test
    @DisplayName("a group linked to itself couples each member to itself")
    void selfLinkIsPerMember() {
        SystemSpec spec = SystemSpec.builder("self").name("self").objectType(dial())
                .object(new ObjectSpec("crowd", "dial", "crowd", Map.of(), Set.of(), Map.of(), 3))
                .link(new LinkSpec("inner", "inner", new VariableRef.OfObject("crowd", "out"),
                        new VariableRef.OfObject("crowd", "in"), 1.0, 0, Transfer.linear(), false,
                        Coupling.of(Coupling.Mode.ONE_TO_ONE)))
                .settings(new SimulationSettings(1, 0, 0L, 1, false)).build();

        Engine engine = new Engine(spec, List.of());
        SimulationState state = engine.createInitialState(SEED);
        List<ObjectState> members = state.membersOf("crowd");
        members.get(0).set("out", 5.0);
        members.get(1).set("out", 6.0);
        members.get(2).set("out", 7.0);
        engine.tick(state);

        assertThat(state.membersOf("crowd").stream().map(member -> member.get("in")))
                .containsExactly(5.0, 6.0, 7.0);
    }

    @Test
    @DisplayName("unset coupling behaves as it did before coupling existed")
    void autoInfersFromTheEndpoints() {
        SystemSpec spec = system(1, 1, Coupling.AUTO);
        SimulationState state = primed(spec, 3.5);
        assertThat(targetValues(state)).containsExactly(3.5);
    }

    @Test
    @DisplayName("a group has one real object per member, each with its own state")
    void membersAreIndependentObjects() {
        SystemSpec spec = system(1, 500, Coupling.of(Coupling.Mode.ONE_TO_MANY));
        Engine engine = new Engine(spec, List.of());
        SimulationState state = engine.createInitialState(SEED);

        List<ObjectState> members = state.membersOf("dst");
        assertThat(members).hasSize(500);
        assertThat(members.stream().map(ObjectState::id).distinct()).hasSize(500);
        assertThat(members.get(0).id()).isEqualTo("dst#1");
        // A single object keeps its plain id, which is what stops group support renaming every
        // series in every system authored before it existed.
        assertThat(state.membersOf("src").get(0).id()).isEqualTo("src");
    }
}
