package dev.fsp.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.engine.spec.LinkSpec;
import dev.fsp.engine.spec.VariableRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoopDetectorTest {

    private static LinkSpec link(String id, String from, String to, double gain, int delay) {
        return new LinkSpec(id, id, new VariableRef.Global(from), new VariableRef.Global(to), gain, delay,
                Transfer.linear(), false);
    }

    @Test
    void findsNothingInAnAcyclicGraph() {
        LoopDetector detector = new LoopDetector(List.of(link("a", "x", "y", 1.0, 0), link("b", "y", "z", 1.0, 0)));

        assertThat(detector.detect()).isEmpty();
        assertThat(detector.hasLoops()).isFalse();
    }

    @Test
    @DisplayName("two positive links form a reinforcing loop")
    void detectsAReinforcingLoop() {
        LoopDetector detector = new LoopDetector(List.of(link("a", "x", "y", 0.5, 0), link("b", "y", "x", 0.5, 0)));

        List<FeedbackLoop> loops = detector.detect();

        assertThat(loops).singleElement().satisfies(loop -> {
            assertThat(loop.polarity()).isEqualTo(FeedbackLoop.Polarity.REINFORCING);
            assertThat(loop.linkIds()).containsExactlyInAnyOrder("a", "b");
            assertThat(loop.length()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("one negative link makes the loop balancing")
    void detectsABalancingLoop() {
        LoopDetector detector = new LoopDetector(List.of(link("a", "x", "y", 0.5, 0), link("b", "y", "x", -0.5, 0)));

        assertThat(detector.detect()).singleElement()
                .satisfies(loop -> assertThat(loop.polarity()).isEqualTo(FeedbackLoop.Polarity.BALANCING));
    }

    @Test
    void twoNegativeLinksCancelBackToReinforcing() {
        LoopDetector detector = new LoopDetector(List.of(link("a", "x", "y", -0.5, 0), link("b", "y", "x", -0.5, 0)));

        assertThat(detector.detect()).singleElement()
                .satisfies(loop -> assertThat(loop.polarity()).isEqualTo(FeedbackLoop.Polarity.REINFORCING));
    }

    @Test
    void sumsDelaysAroundTheLoopAndFlagsOscillationRisk() {
        LoopDetector detector = new LoopDetector(List.of(link("a", "x", "y", 0.5, 3), link("b", "y", "x", -0.5, 2)));

        FeedbackLoop loop = detector.detect().getFirst();

        assertThat(loop.totalDelay()).isEqualTo(5);
        assertThat(loop.isOscillationRisk()).isTrue();
    }

    @Test
    void anUndelayedBalancingLoopIsNotAnOscillationRisk() {
        LoopDetector detector = new LoopDetector(List.of(link("a", "x", "y", 0.5, 0), link("b", "y", "x", -0.5, 0)));

        assertThat(detector.detect().getFirst().isOscillationRisk()).isFalse();
    }

    @Test
    void findsASelfLoop() {
        LoopDetector detector = new LoopDetector(List.of(link("a", "x", "x", 0.5, 0)));

        assertThat(detector.detect()).singleElement().satisfies(loop -> assertThat(loop.length()).isEqualTo(1));
    }

    @Test
    void reportsEachLoopOnceRatherThanOncePerRotation() {
        LoopDetector detector = new LoopDetector(
                List.of(link("a", "x", "y", 1.0, 0), link("b", "y", "z", 1.0, 0), link("c", "z", "x", 1.0, 0)));

        assertThat(detector.detect()).hasSize(1);
    }

    @Test
    void findsSeveralDistinctLoopsInTheSameGraph() {
        LoopDetector detector = new LoopDetector(List.of(link("a", "x", "y", 1.0, 0), link("b", "y", "x", 1.0, 0),
                link("c", "y", "z", 1.0, 0), link("d", "z", "y", -1.0, 0)));

        List<FeedbackLoop> loops = detector.detect();

        assertThat(loops).hasSize(2);
        assertThat(loops).extracting(FeedbackLoop::polarity).containsExactlyInAnyOrder(
                FeedbackLoop.Polarity.REINFORCING, FeedbackLoop.Polarity.BALANCING);
    }

    @Test
    void stopsAtTheLoopCapOnAPathologicalGraph() {
        // A fully connected graph has factorially many cycles; the cap keeps publish responsive.
        List<LinkSpec> links = new ArrayList<>();
        String[] nodes = {"a", "b", "c", "d", "e", "f", "g"};
        for (String from : nodes) {
            for (String to : nodes) {
                if (!from.equals(to)) {
                    links.add(link(from + to, from, to, 1.0, 0));
                }
            }
        }

        assertThat(new LoopDetector(links, 25).detect()).hasSizeLessThanOrEqualTo(25);
    }
}
