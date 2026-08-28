package dev.fsp.engine.state;

import java.util.Arrays;

/**
 * A fixed-length pipeline of pending contributions for one link.
 *
 * <p>Delay is what separates a feedback model from a set of simultaneous equations. Consequences
 * that arrive late are why systems overshoot, oscillate and occasionally never settle, so links
 * carry their in-flight values here rather than applying them the moment they are computed.
 */
public final class DelayLine {

    private final double[] slots;
    private int head;

    public DelayLine(int delayTicks) {
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must not be negative: " + delayTicks);
        }
        // A zero-delay link still needs one slot: it is pushed and popped within the same tick.
        this.slots = new double[delayTicks + 1];
    }

    public int delayTicks() {
        return slots.length - 1;
    }

    /**
     * Pushes this tick's contribution in and returns the one that has finished travelling.
     *
     * <p>A contribution pushed at tick {@code t} comes back out at {@code t + delayTicks}, so a
     * zero-delay link returns what was just pushed rather than waiting a tick.
     */
    public double advance(double contribution) {
        // Write delayTicks slots ahead of the read head; with length delayTicks + 1 that is the
        // slot immediately behind it in the ring.
        int writeAt = (head + slots.length - 1) % slots.length;
        slots[writeAt] = contribution;
        double due = slots[head];
        slots[head] = 0.0;
        head = (head + 1) % slots.length;
        return due;
    }

    /** Value that will be released next, without advancing. */
    public double peek() {
        return slots[head];
    }

    /** True while contributions are still in flight, so callers can tell a run has not settled. */
    public boolean hasPendingContributions() {
        for (double slot : slots) {
            if (slot != 0.0) {
                return true;
            }
        }
        return false;
    }

    /** Snapshot of the in-flight values, oldest first, for checkpointing and inspection. */
    public double[] pending() {
        double[] ordered = new double[slots.length];
        for (int i = 0; i < slots.length; i++) {
            ordered[i] = slots[(head + i) % slots.length];
        }
        return ordered;
    }

    /** Restores in-flight values produced by {@link #pending()}. */
    public void restore(double[] ordered) {
        if (ordered.length != slots.length) {
            throw new IllegalArgumentException(
                    "expected " + slots.length + " pending values but got " + ordered.length);
        }
        System.arraycopy(ordered, 0, slots, 0, ordered.length);
        head = 0;
    }

    public DelayLine copy() {
        DelayLine copy = new DelayLine(delayTicks());
        System.arraycopy(slots, 0, copy.slots, 0, slots.length);
        copy.head = head;
        return copy;
    }

    @Override
    public String toString() {
        return "DelayLine" + Arrays.toString(pending());
    }
}
