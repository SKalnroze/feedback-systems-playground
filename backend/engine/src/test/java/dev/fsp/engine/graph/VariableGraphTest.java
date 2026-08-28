package dev.fsp.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.engine.memory.MemorySettings;
import dev.fsp.engine.spec.LinkSpec;
import dev.fsp.engine.spec.ObjectSpec;
import dev.fsp.engine.spec.ObjectTypeSpec;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.spec.VariableRef;
import dev.fsp.engine.spec.VariableRef.OfType.Aggregate;
import dev.fsp.engine.spec.VariableSpec;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VariableGraphTest {

    private static final Offset<Double> PRECISE = Offset.offset(1e-9);

    private SimulationState state;

    private static ObjectTypeSpec personType() {
        return new ObjectTypeSpec("person", "Person",
                List.of(VariableSpec.unitStock("trust", 0.5), VariableSpec.unitStock("stress", 0.0),
                        VariableSpec.constant("openness", 0.4),
                        new VariableSpec("mood", "mood", VariableSpec.Kind.AUXILIARY, 0.0, -1.0, 1.0)),
                MemorySettings.DEFAULT, Set.of(), Map.of());
    }

    @BeforeEach
    void setUp() {
        state = new SimulationState(5L);
        state.addObject(new ObjectState("alice", "person").withVariable("trust", 0.2).withVariable("stress", 0.0)
                .withVariable("openness", 0.4).withVariable("mood", 0.0));
        state.addObject(new ObjectState("bob", "person").withVariable("trust", 0.8).withVariable("stress", 0.0)
                .withVariable("openness", 0.4).withVariable("mood", 0.0));
        state.setGlobal("tension", 0.0);
    }

    private static SystemSpec specWith(LinkSpec... links) {
        SystemSpec.Builder builder = SystemSpec.builder("graph").objectType(personType())
                .object(ObjectSpec.of("alice", "person")).object(ObjectSpec.of("bob", "person"))
                .globalVariable(VariableSpec.unitStock("tension", 0.0));
        for (LinkSpec link : links) {
            builder.link(link);
        }
        return builder.build();
    }

    @Test
    void stocksAccumulateContributionsWhileAuxiliariesAreReplaced() {
        VariableGraph graph = new VariableGraph(specWith(
                LinkSpec.of("to-stress", new VariableRef.OfObject("alice", "trust"),
                        new VariableRef.OfObject("alice", "stress"), 0.5),
                LinkSpec.of("to-mood", new VariableRef.OfObject("alice", "trust"),
                        new VariableRef.OfObject("alice", "mood"), 0.5)));

        graph.propagate(state);
        graph.propagate(state);

        // The stock has taken 0.1 twice; the auxiliary shows only the latest reading.
        assertThat(state.object("alice").get("stress")).isCloseTo(0.2, PRECISE);
        assertThat(state.object("alice").get("mood")).isCloseTo(0.1, PRECISE);
    }

    @Test
    void everyLinkReadsTheValueHeldAtTheStartOfTheTick() {
        VariableGraph graph = new VariableGraph(specWith(
                LinkSpec.of("a", new VariableRef.OfObject("alice", "trust"),
                        new VariableRef.OfObject("bob", "stress"), 1.0),
                LinkSpec.of("b", new VariableRef.OfObject("alice", "trust"),
                        new VariableRef.OfObject("alice", "trust"), 1.0)));

        graph.propagate(state);

        // Both links saw trust at 0.2, even though one of them wrote to it.
        assertThat(state.object("bob").get("stress")).isCloseTo(0.2, PRECISE);
        assertThat(state.object("alice").get("trust")).isCloseTo(0.4, PRECISE);
    }

    @Test
    void severalLinksIntoOneVariableAreSummed() {
        VariableGraph graph = new VariableGraph(specWith(
                LinkSpec.of("a", new VariableRef.OfObject("alice", "trust"),
                        new VariableRef.Global("tension"), 0.5),
                LinkSpec.of("b", new VariableRef.OfObject("bob", "trust"), new VariableRef.Global("tension"), 0.5)));

        Map<String, Double> applied = graph.propagate(state);

        assertThat(applied.get("global.tension")).isCloseTo(0.5, PRECISE);
        assertThat(state.global("tension")).isCloseTo(0.5, PRECISE);
    }

    @Test
    void readsAggregatesAcrossEveryObjectOfAType() {
        VariableGraph graph = new VariableGraph(specWith());

        assertThat(graph.read(state, new VariableRef.OfType("person", "trust", Aggregate.MEAN))).isCloseTo(0.5,
                PRECISE);
        assertThat(graph.read(state, new VariableRef.OfType("person", "trust", Aggregate.SUM))).isCloseTo(1.0,
                PRECISE);
        assertThat(graph.read(state, new VariableRef.OfType("person", "trust", Aggregate.MIN))).isCloseTo(0.2,
                PRECISE);
        assertThat(graph.read(state, new VariableRef.OfType("person", "trust", Aggregate.MAX))).isCloseTo(0.8,
                PRECISE);
        assertThat(graph.read(state, new VariableRef.OfType("person", "trust", Aggregate.SPREAD))).isCloseTo(0.6,
                PRECISE);
        assertThat(graph.read(state, new VariableRef.OfType("person", "trust", Aggregate.VARIANCE))).isCloseTo(0.09,
                PRECISE);
    }

    @Test
    void anAggregateOverNobodyReadsAsZero() {
        VariableGraph graph = new VariableGraph(specWith());

        assertThat(graph.read(state, new VariableRef.OfType("ghosts", "trust", Aggregate.MEAN))).isZero();
    }

    @Test
    void missingObjectsAndGlobalsReadAsZeroRatherThanThrowing() {
        VariableGraph graph = new VariableGraph(specWith());

        assertThat(graph.read(state, new VariableRef.OfObject("ghost", "trust"))).isZero();
        assertThat(graph.read(state, new VariableRef.Global("nowhere"))).isZero();
    }

    @Test
    void aggregatesCanDriveAnIndividualsVariable() {
        VariableGraph graph = new VariableGraph(specWith(LinkSpec.of("group-pressure",
                new VariableRef.OfType("person", "trust", Aggregate.MEAN),
                new VariableRef.OfObject("alice", "stress"), 0.5)));

        graph.propagate(state);

        assertThat(state.object("alice").get("stress")).isCloseTo(0.25, PRECISE);
    }

    @Test
    void rateLinksRespondToChangeRatherThanLevel() {
        VariableGraph graph = new VariableGraph(specWith(LinkSpec
                .of("shock", new VariableRef.OfObject("alice", "trust"), new VariableRef.OfObject("alice", "stress"),
                        1.0)
                .onRateOfChange()));
        graph.primeRateBaseline(state);

        graph.propagate(state);
        assertThat(state.object("alice").get("stress")).isZero();

        state.object("alice").set("trust", 0.5);
        graph.propagate(state);
        assertThat(state.object("alice").get("stress")).isCloseTo(0.3, PRECISE);
    }

    @Test
    void constantsAreNeverWrittenTo() {
        VariableGraph graph = new VariableGraph(specWith(LinkSpec.of("nope",
                new VariableRef.OfObject("alice", "trust"), new VariableRef.OfObject("alice", "openness"), 1.0)));

        graph.propagate(state);

        assertThat(state.object("alice").get("openness")).isCloseTo(0.4, PRECISE);
    }

    @Test
    void writesToUnknownTargetsAreIgnored() {
        SystemSpec spec = SystemSpec.builder("graph").objectType(personType())
                .object(ObjectSpec.of("alice", "person"))
                .link(LinkSpec.of("ghost-target", new VariableRef.OfObject("alice", "trust"),
                        new VariableRef.OfObject("ghost", "trust"), 1.0))
                .link(LinkSpec.of("unknown-global", new VariableRef.OfObject("alice", "trust"),
                        new VariableRef.Global("nowhere"), 1.0))
                .build();
        VariableGraph graph = new VariableGraph(spec);

        graph.propagate(state);

        assertThat(state.hasGlobal("nowhere")).isFalse();
    }

    @Test
    void writesAreClampedToTheDeclaredBounds() {
        VariableGraph graph = new VariableGraph(specWith(LinkSpec.of("push",
                new VariableRef.OfObject("bob", "trust"), new VariableRef.OfObject("alice", "trust"), 5.0)));

        graph.propagate(state);

        assertThat(state.object("alice").get("trust")).isCloseTo(1.0, PRECISE);
    }
}
