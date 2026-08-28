package dev.fsp.engine.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fsp.engine.event.TargetSelector.Target;
import dev.fsp.engine.expr.NumExpr;
import dev.fsp.engine.expr.Predicate;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TargetSelectorTest {

    private SimulationState state;

    @BeforeEach
    void setUp() {
        state = new SimulationState(7L);
        state.addObject(new ObjectState("alice", "person").withVariable("trust", 0.9));
        state.addObject(new ObjectState("bob", "person").withVariable("trust", 0.2));
        state.addObject(new ObjectState("carol", "person").withVariable("trust", 0.7));
        state.addObject(new ObjectState("team", "group").withVariable("trust", 0.5));
    }

    private Rng rng() {
        return Rng.of(7L, 1L, RngStream.EVENT_TARGETING);
    }

    private static List<String> ids(List<Target> targets) {
        return targets.stream().map(target -> target.primary().id()).toList();
    }

    @Test
    void globalOnlySelectsASingleTargetlessApplication() {
        List<Target> targets = TargetSelector.GlobalOnly.INSTANCE.select(state, rng());

        assertThat(targets).hasSize(1);
        assertThat(targets.getFirst().primary()).isNull();
        assertThat(targets.getFirst().isPair()).isFalse();
    }

    @Test
    void everyoneSelectsAllActiveObjects() {
        assertThat(ids(TargetSelector.Everyone.all().select(state, rng()))).containsExactly("alice", "bob", "carol",
                "team");
    }

    @Test
    void everyoneCanBeNarrowedToOneType() {
        assertThat(ids(new TargetSelector.Everyone("person").select(state, rng()))).containsExactly("alice", "bob",
                "carol");
    }

    @Test
    void inactiveObjectsAreNeverSelected() {
        state.object("bob").deactivate();

        assertThat(ids(TargetSelector.Everyone.all().select(state, rng()))).doesNotContain("bob");
    }

    @Test
    void randomKPicksTheRequestedNumberWithoutRepeats() {
        List<Target> targets = new TargetSelector.RandomK("person", 2).select(state, rng());

        assertThat(targets).hasSize(2);
        assertThat(ids(targets)).doesNotHaveDuplicates().isSubsetOf("alice", "bob", "carol");
    }

    @Test
    void randomKCapsAtThePoolSize() {
        assertThat(new TargetSelector.RandomK("person", 99).select(state, rng())).hasSize(3);
    }

    @Test
    void matchingSelectsOnlyObjectsSatisfyingTheCondition() {
        TargetSelector selector = new TargetSelector.Matching("person",
                new Predicate.Compare(Predicate.Compare.Op.GT, NumExpr.self("trust"), NumExpr.of(0.5)));

        assertThat(ids(selector.select(state, rng()))).containsExactly("alice", "carol");
    }

    @Test
    void specificSelectsNamedObjectsInStateOrder() {
        TargetSelector selector = new TargetSelector.Specific(Set.of("carol", "alice"));

        assertThat(ids(selector.select(state, rng()))).containsExactly("alice", "carol");
    }

    @Test
    void specificIgnoresUnknownIds() {
        assertThat(new TargetSelector.Specific(Set.of("ghost")).select(state, rng())).isEmpty();
    }

    @Test
    void randomPairSelectsTwoDistinctObjects() {
        List<Target> targets = TargetSelector.RandomPair.one().select(state, rng());

        assertThat(targets).hasSize(1);
        Target pair = targets.getFirst();
        assertThat(pair.isPair()).isTrue();
        assertThat(pair.primary().id()).isNotEqualTo(pair.secondary().id());
    }

    @Test
    void randomPairYieldsNothingWhenThereIsNobodyToPairWith() {
        SimulationState lonely = new SimulationState(1L);
        lonely.addObject(new ObjectState("alice", "person"));

        assertThat(TargetSelector.RandomPair.one().select(lonely, rng())).isEmpty();
    }

    @Test
    void namedPairSelectsExactlyThoseTwoInOrder() {
        List<Target> targets = new TargetSelector.NamedPair("carol", "alice").select(state, rng());

        assertThat(targets).hasSize(1);
        assertThat(targets.getFirst().primary().id()).isEqualTo("carol");
        assertThat(targets.getFirst().secondary().id()).isEqualTo("alice");
    }

    @Test
    void namedPairYieldsNothingWhenEitherPartyIsMissingOrInactive() {
        assertThat(new TargetSelector.NamedPair("alice", "ghost").select(state, rng())).isEmpty();

        state.object("bob").deactivate();
        assertThat(new TargetSelector.NamedPair("alice", "bob").select(state, rng())).isEmpty();
    }

    @Test
    void namedPairRejectsAPairOfOne() {
        assertThatThrownBy(() -> new TargetSelector.NamedPair("alice", "alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSelector.NamedPair("alice", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allPairsCoversEveryOrderedCombination() {
        List<Target> targets = new TargetSelector.AllPairs("person").select(state, rng());

        assertThat(targets).hasSize(6);
        assertThat(targets).allMatch(Target::isPair);
        assertThat(targets).noneMatch(target -> target.primary() == target.secondary());
    }

    @Test
    void rejectsNonPositiveCounts() {
        assertThatThrownBy(() -> new TargetSelector.RandomK("person", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TargetSelector.RandomPair("person", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everySelectorReportsADistinctKind() {
        List<TargetSelector> selectors = List.of(TargetSelector.GlobalOnly.INSTANCE, TargetSelector.Everyone.all(),
                new TargetSelector.RandomK("person", 1), new TargetSelector.Matching(null, Predicate.always()),
                new TargetSelector.Specific(Set.of("alice")), TargetSelector.RandomPair.one(),
                new TargetSelector.NamedPair("alice", "bob"), new TargetSelector.AllPairs("person"));

        assertThat(selectors.stream().map(TargetSelector::kind).distinct().count()).isEqualTo(selectors.size());
    }
}
