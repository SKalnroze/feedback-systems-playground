package dev.fsp.engine.spec;

import dev.fsp.engine.graph.Transfer;

/**
 * A causal edge: changes in the source variable push the target variable.
 *
 * @param gain       multiplier applied before the transfer function; the edge's strength and sign
 * @param delayTicks how long the effect takes to arrive
 * @param transfer   the shape of the response
 * @param usesRate   when true the link responds to the source's change since last tick rather than
 *                   its level, which is how "a sudden drop in trust causes withdrawal" differs from
 *                   "low trust causes withdrawal"
 * @param coupling   how the edge connects members when either endpoint is a group
 */
public record LinkSpec(String id, String label, VariableRef source, VariableRef target, double gain, int delayTicks,
        Transfer transfer, boolean usesRate, Coupling coupling) {

    /** Keeps every caller written before coupling existed compiling, with inference as before. */
    public LinkSpec(String id, String label, VariableRef source, VariableRef target, double gain, int delayTicks,
            Transfer transfer, boolean usesRate) {
        this(id, label, source, target, gain, delayTicks, transfer, usesRate, Coupling.AUTO);
    }

    public LinkSpec {
        if (coupling == null) {
            coupling = Coupling.AUTO;
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("link id must not be blank");
        }
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must not be negative for link " + id);
        }
        if (!target.isWritable()) {
            throw new IllegalArgumentException(
                    "link " + id + " targets an aggregate, which cannot be written to: " + target.seriesKey());
        }
        if (transfer == null) {
            throw new IllegalArgumentException("link " + id + " has no transfer function");
        }
        label = label == null || label.isBlank() ? id : label;
    }

    public static LinkSpec of(String id, VariableRef source, VariableRef target, double gain) {
        return new LinkSpec(id, id, source, target, gain, 0, Transfer.linear(), false, Coupling.AUTO);
    }

    public LinkSpec delayedBy(int ticks) {
        return new LinkSpec(id, label, source, target, gain, ticks, transfer, usesRate, coupling);
    }

    public LinkSpec through(Transfer newTransfer) {
        return new LinkSpec(id, label, source, target, gain, delayTicks, newTransfer, usesRate, coupling);
    }

    public LinkSpec onRateOfChange() {
        return new LinkSpec(id, label, source, target, gain, delayTicks, transfer, true, coupling);
    }

    public LinkSpec coupledBy(Coupling newCoupling) {
        return new LinkSpec(id, label, source, target, gain, delayTicks, transfer, usesRate, newCoupling);
    }

    /** Contribution this link produces from a source reading, before delay. */
    public double contribution(double sourceValue) {
        return transfer.apply(gain * sourceValue);
    }
}
