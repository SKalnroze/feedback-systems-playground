package dev.fsp.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class TransferTest {

    private static final Offset<Double> PRECISE = Offset.offset(1e-9);
    private static final Offset<Double> LOOSE = Offset.offset(1e-3);

    @Test
    void linearPassesItsInputThrough() {
        assertThat(Transfer.linear().apply(0.42)).isCloseTo(0.42, PRECISE);
        assertThat(Transfer.linear().apply(-3.0)).isCloseTo(-3.0, PRECISE);
    }

    @Test
    void sigmoidSaturatesAtBothEnds() {
        Transfer transfer = new Transfer.Sigmoid(2.0, 1.0, 1.0);

        assertThat(transfer.apply(1.0)).isCloseTo(0.5, PRECISE);
        assertThat(transfer.apply(20.0)).isCloseTo(1.0, LOOSE);
        assertThat(transfer.apply(-20.0)).isCloseTo(0.0, LOOSE);
    }

    @Test
    void tanhSaturatesSymmetricallyAndKeepsSign() {
        Transfer transfer = new Transfer.Tanh(1.0, 2.0);

        assertThat(transfer.apply(0.0)).isZero();
        assertThat(transfer.apply(10.0)).isCloseTo(2.0, LOOSE);
        assertThat(transfer.apply(-10.0)).isCloseTo(-2.0, LOOSE);
    }

    @Test
    void thresholdSwitchesAtItsCrossingPoint() {
        Transfer transfer = new Transfer.Threshold(0.5, 0.0, 1.0);

        assertThat(transfer.apply(0.49)).isZero();
        assertThat(transfer.apply(0.5)).isCloseTo(1.0, PRECISE);
    }

    @Test
    void saturatingClipsToItsBand() {
        Transfer transfer = new Transfer.Saturating(-1.0, 1.0);

        assertThat(transfer.apply(5.0)).isCloseTo(1.0, PRECISE);
        assertThat(transfer.apply(-5.0)).isCloseTo(-1.0, PRECISE);
        assertThat(transfer.apply(0.3)).isCloseTo(0.3, PRECISE);
    }

    @Test
    void logarithmicGivesDiminishingReturns() {
        Transfer transfer = new Transfer.Logarithmic(1.0);

        double firstUnit = transfer.apply(1.0) - transfer.apply(0.0);
        double tenthUnit = transfer.apply(10.0) - transfer.apply(9.0);

        assertThat(tenthUnit).isLessThan(firstUnit);
        assertThat(transfer.apply(-1.0)).isCloseTo(-transfer.apply(1.0), PRECISE);
    }

    @Test
    void powerCurveAcceleratesAboveOneAndDampsBelow() {
        assertThat(new Transfer.PowerCurve(2.0).apply(3.0)).isCloseTo(9.0, PRECISE);
        assertThat(new Transfer.PowerCurve(0.5).apply(9.0)).isCloseTo(3.0, PRECISE);
        assertThat(new Transfer.PowerCurve(2.0).apply(-3.0)).isCloseTo(-9.0, PRECISE);
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new Transfer.Sigmoid(0.0, 0.0, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer.Tanh(0.0, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer.Saturating(1.0, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer.Logarithmic(0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer.PowerCurve(0.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyTransferReportsADistinctKind() {
        List<Transfer> transfers = List.of(Transfer.linear(), new Transfer.Sigmoid(1.0, 0.0, 1.0),
                new Transfer.Tanh(1.0, 1.0), new Transfer.Threshold(0.5, 0.0, 1.0), new Transfer.Saturating(0.0, 1.0),
                new Transfer.Logarithmic(1.0), new Transfer.PowerCurve(2.0));

        assertThat(transfers.stream().map(Transfer::kind).distinct().count()).isEqualTo(transfers.size());
    }
}
