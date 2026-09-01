package dev.fsp.modules.learning;

import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.memory.MemorySettings;
import dev.fsp.engine.spec.ObjectTypeSpec;
import dev.fsp.engine.spec.VariableSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The vocabulary of the learning module.
 *
 * <p>This module exists to show the engine is not about people. The interpersonal pack models what
 * objects remember <em>about each other</em>; here an object remembers <em>material</em>, and the
 * feedback loop runs through the learner's own confidence rather than through a relationship.
 *
 * <p>It also exercises the parts of the memory model the first module never touches. Interpersonal
 * memories fade on a power law with no consolidation; study traces are the case Ebbinghaus was
 * actually measuring, where each successful recall makes the next one last longer. That is the
 * spacing effect, and it falls out of the decay model rather than being coded here.
 */
public final class Learner {

    public static final String TYPE_ID = "learner";
    public static final String TOPIC_TYPE_ID = "topic";

    /** Willingness to sit down and study at all. Fed by success, drained by failure. */
    public static final String MOTIVATION = "motivation";

    /** What the learner believes they know. Diverges from recall, which is the interesting part. */
    public static final String CONFIDENCE = "confidence";

    /** Capacity for study this tick. Cramming spends it faster than it recovers. */
    public static final String ATTENTION = "attention";

    /** Measured performance, moved only by tests rather than by studying. */
    public static final String ATTAINMENT = "attainment";

    /** How quickly this learner consolidates. Constant: it is a property, not a state. */
    public static final String APTITUDE = "aptitude";

    /** How much material the topic holds, which scales how long it takes to get through. */
    public static final String DIFFICULTY = "difficulty";

    private Learner() {
    }

    /**
     * Study traces consolidate with each review.
     *
     * <p>Base stability is deliberately short: a single reading is forgotten within days, and the
     * whole point of the module is that repetition, not initial effort, is what makes it stick.
     */
    public static MemorySettings defaultMemory() {
        return new MemorySettings(new DecayModel.Ebbinghaus(12.0, 1.9, DecayModel.DecayCommon.NONE), 0.08, 0, false, 0.6);
    }

    public static ObjectTypeSpec learnerType() {
        return learnerType(defaultMemory());
    }

    public static ObjectTypeSpec learnerType(MemorySettings memory) {
        return new ObjectTypeSpec(TYPE_ID, "Learner",
                List.of(VariableSpec.unitStock(MOTIVATION, 0.6), VariableSpec.unitStock(CONFIDENCE, 0.3),
                        VariableSpec.unitStock(ATTENTION, 1.0), VariableSpec.unitStock(ATTAINMENT, 0.0),
                        VariableSpec.constant(APTITUDE, 0.5)),
                memory, Set.of(), Map.of());
    }

    /** A topic is a thing to be learned. It has no memory of its own; it is what gets remembered. */
    public static ObjectTypeSpec topicType() {
        return new ObjectTypeSpec(TOPIC_TYPE_ID, "Topic",
                List.of(VariableSpec.constant(DIFFICULTY, 0.5)),
                new MemorySettings(DecayModel.NoDecay.INSTANCE, 1.0, 0, true, 0.6), Set.of(), Map.of());
    }
}
