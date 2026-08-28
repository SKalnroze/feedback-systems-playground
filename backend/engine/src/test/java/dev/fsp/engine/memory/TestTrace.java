package dev.fsp.engine.memory;

import java.util.ArrayList;
import java.util.List;

/** Minimal {@link Trace} for exercising decay maths without any simulation state. */
record TestTrace(double initialStrength, long createdTick, long originTick, List<Reactivation> reactivations,
                 int similarCount) implements Trace {

    static TestTrace fresh(double initialStrength) {
        return new TestTrace(initialStrength, 0L, 0L, List.of(), 0);
    }

    TestTrace withSimilar(int count) {
        return new TestTrace(initialStrength, createdTick, originTick, reactivations, count);
    }

    /** Adds a reactivation, mirroring what {@code MemoryStore} does when a trigger fires. */
    TestTrace reactivated(long tick, double boost, double stabilityGain, boolean resetClock) {
        List<Reactivation> next = new ArrayList<>(reactivations);
        next.add(new Reactivation(tick, boost, stabilityGain, resetClock, "test"));
        return new TestTrace(initialStrength, createdTick, resetClock ? tick : originTick, List.copyOf(next),
                similarCount);
    }

    TestTrace reactivatedAt(long tick) {
        return reactivated(tick, 0.0, 0.0, false);
    }
}
