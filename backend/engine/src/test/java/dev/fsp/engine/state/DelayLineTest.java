package dev.fsp.engine.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class DelayLineTest {

    private static final Offset<Double> PRECISE = Offset.offset(1e-9);

    @Test
    void aZeroDelayLineDeliversImmediately() {
        DelayLine line = new DelayLine(0);

        assertThat(line.advance(0.5)).isCloseTo(0.5, PRECISE);
        assertThat(line.advance(0.25)).isCloseTo(0.25, PRECISE);
        assertThat(line.delayTicks()).isZero();
    }

    @Test
    void aDelayedLineHoldsContributionsForExactlyTheStatedNumberOfTicks() {
        DelayLine line = new DelayLine(3);

        assertThat(line.advance(1.0)).isZero();
        assertThat(line.advance(0.0)).isZero();
        assertThat(line.advance(0.0)).isZero();
        assertThat(line.advance(0.0)).isCloseTo(1.0, PRECISE);
        assertThat(line.advance(0.0)).isZero();
    }

    @Test
    void severalContributionsStayInOrder() {
        DelayLine line = new DelayLine(2);

        line.advance(1.0);
        line.advance(2.0);

        assertThat(line.advance(3.0)).isCloseTo(1.0, PRECISE);
        assertThat(line.advance(0.0)).isCloseTo(2.0, PRECISE);
        assertThat(line.advance(0.0)).isCloseTo(3.0, PRECISE);
    }

    @Test
    void reportsWhetherAnythingIsStillInFlight() {
        DelayLine line = new DelayLine(2);

        assertThat(line.hasPendingContributions()).isFalse();
        line.advance(1.0);
        assertThat(line.hasPendingContributions()).isTrue();
        line.advance(0.0);
        line.advance(0.0);
        assertThat(line.hasPendingContributions()).isFalse();
    }

    @Test
    void peekShowsWhatArrivesNextWithoutConsumingIt() {
        DelayLine line = new DelayLine(1);
        line.advance(0.75);

        assertThat(line.peek()).isCloseTo(0.75, PRECISE);
        assertThat(line.peek()).isCloseTo(0.75, PRECISE);
        assertThat(line.advance(0.0)).isCloseTo(0.75, PRECISE);
    }

    @Test
    void pendingAndRestoreRoundTrip() {
        DelayLine line = new DelayLine(3);
        line.advance(1.0);
        line.advance(2.0);

        DelayLine restored = new DelayLine(3);
        restored.restore(line.pending());

        assertThat(restored.advance(0.0)).isEqualTo(line.advance(0.0));
        assertThat(restored.advance(0.0)).isEqualTo(line.advance(0.0));
    }

    @Test
    void copiesAreIndependent() {
        DelayLine line = new DelayLine(2);
        line.advance(1.0);

        DelayLine copy = line.copy();
        line.advance(9.0);

        assertThat(copy.advance(0.0)).isZero();
        assertThat(copy.advance(0.0)).isCloseTo(1.0, PRECISE);
    }

    @Test
    void rejectsInvalidConstructionAndRestore() {
        assertThatThrownBy(() -> new DelayLine(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelayLine(2).restore(new double[] {1.0}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expected 3");
    }

    @Test
    void describesItsContents() {
        DelayLine line = new DelayLine(1);
        line.advance(0.5);

        assertThat(line.toString()).startsWith("DelayLine[");
    }
}
