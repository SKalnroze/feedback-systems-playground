package dev.fsp.modules.learning;

import dev.fsp.engine.memory.MemoryRecord;
import dev.fsp.engine.module.InteractionContext;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.state.ObjectState;
import java.util.ArrayList;
import java.util.List;

/** Shared selection helpers for the learning rules. */
final class Topics {

    private Topics() {
    }

    static List<ObjectState> of(InteractionContext context, String typeId) {
        List<ObjectState> matching = new ArrayList<>();
        for (ObjectState object : context.state().activeObjects()) {
            if (object.typeId().equals(typeId)) {
                matching.add(object);
            }
        }
        return matching;
    }

    /** Current strength of what a learner holds about a topic, zero when they have never seen it. */
    static double recall(InteractionContext context, ObjectState learner, ObjectState topic) {
        double best = 0.0;
        for (MemoryRecord memory : context.state().memories().about(learner.id(), topic.id())) {
            best = Math.max(best, memory.strengthAt(context.tick()));
        }
        return best;
    }

    /**
     * Picks what to study next.
     *
     * <p>Weighted towards familiar material by {@code familiarityBias}: at zero the learner studies
     * whatever is weakest, which is optimal and unlike most people; at one they restudy what they
     * know best, which feels productive and is close to useless.
     */
    static ObjectState choose(InteractionContext context, ObjectState learner, List<ObjectState> topics,
            double familiarityBias, Rng rng) {
        double total = 0.0;
        double[] weights = new double[topics.size()];
        for (int i = 0; i < topics.size(); i++) {
            double strength = recall(context, learner, topics.get(i));
            // Never zero: an unseen topic must remain reachable or nothing new is ever started.
            weights[i] = 0.05 + familiarityBias * strength + (1.0 - familiarityBias) * (1.0 - strength);
            total += weights[i];
        }
        if (total <= 0.0) {
            return topics.isEmpty() ? null : topics.get(0);
        }
        double roll = rng.nextDouble() * total;
        for (int i = 0; i < topics.size(); i++) {
            roll -= weights[i];
            if (roll <= 0.0) {
                return topics.get(i);
            }
        }
        return topics.get(topics.size() - 1);
    }
}
