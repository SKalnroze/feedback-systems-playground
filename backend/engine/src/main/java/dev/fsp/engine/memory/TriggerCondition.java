package dev.fsp.engine.memory;

import dev.fsp.engine.expr.Predicate;
import java.util.List;
import java.util.Set;

/**
 * What brings a memory back.
 *
 * <p>Forgetting curves alone make memory a one-way fade. Reactivation is what makes a feedback
 * system out of it: an old slight that resurfaces every time the two people are in a room together
 * never decays away, and that loop is usually the interesting part of the model.
 */
public sealed interface TriggerCondition {

    String kind();

    boolean matches(TriggerContext context, MemoryRecord memory);

    /** Fires when one of the named events happened this tick. */
    record OnEvent(Set<String> eventIds) implements TriggerCondition {

        public OnEvent {
            eventIds = Set.copyOf(eventIds);
        }

        @Override
        public String kind() {
            return "on-event";
        }

        @Override
        public boolean matches(TriggerContext context, MemoryRecord memory) {
            for (String fired : context.firedEventIds()) {
                if (eventIds.contains(fired)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Fires when the memory's subject is involved in something this tick: seeing the person brings
     * back what you remember about them.
     */
    record SubjectPresent() implements TriggerCondition {

        public static final SubjectPresent INSTANCE = new SubjectPresent();

        @Override
        public String kind() {
            return "subject-present";
        }

        @Override
        public boolean matches(TriggerContext context, MemoryRecord memory) {
            return context.objectsInPlay().contains(memory.subjectId());
        }
    }

    /** Fires when a condition over the owner's and subject's variables holds. */
    record OnCondition(Predicate predicate) implements TriggerCondition {

        @Override
        public String kind() {
            return "on-condition";
        }

        @Override
        public boolean matches(TriggerContext context, MemoryRecord memory) {
            return predicate.test(context.evalContextFor(memory));
        }
    }

    /**
     * Fires when the current stimulus resembles the memory closely enough: the smell of the room,
     * the shape of the argument. The mechanism behind involuntary recall.
     */
    record CueSimilarity(double threshold) implements TriggerCondition {

        public CueSimilarity {
            if (threshold < 0.0 || threshold > 1.0) {
                throw new IllegalArgumentException("threshold must be within [0, 1]: " + threshold);
            }
        }

        @Override
        public String kind() {
            return "cue-similarity";
        }

        @Override
        public boolean matches(TriggerContext context, MemoryRecord memory) {
            return memory.similarityTo(context.cue()) >= threshold;
        }
    }

    /** Fires on a fixed rhythm: scheduled rumination, anniversaries, review cycles. */
    record Periodic(long interval, long offset) implements TriggerCondition {

        public Periodic {
            if (interval <= 0L) {
                throw new IllegalArgumentException("interval must be positive: " + interval);
            }
        }

        @Override
        public String kind() {
            return "periodic";
        }

        @Override
        public boolean matches(TriggerContext context, MemoryRecord memory) {
            long since = context.tick() - offset;
            return since >= 0 && since % interval == 0;
        }
    }

    /** Fires at random: intrusive thoughts, with no cue to explain them. */
    record Stochastic(double probability) implements TriggerCondition {

        public Stochastic {
            if (probability < 0.0 || probability > 1.0) {
                throw new IllegalArgumentException("probability must be within [0, 1]: " + probability);
            }
        }

        @Override
        public String kind() {
            return "stochastic";
        }

        @Override
        public boolean matches(TriggerContext context, MemoryRecord memory) {
            return context.rngFor(memory).nextBoolean(probability);
        }
    }

    /**
     * Fires only while the memory's strength sits inside a band. Lets a spec rehearse memories
     * that are on the edge of being lost while leaving vivid ones alone.
     */
    record StrengthBand(double min, double max) implements TriggerCondition {

        public StrengthBand {
            if (min > max) {
                throw new IllegalArgumentException("min must not exceed max: " + min + " > " + max);
            }
        }

        @Override
        public String kind() {
            return "strength-band";
        }

        @Override
        public boolean matches(TriggerContext context, MemoryRecord memory) {
            double strength = memory.strengthAt(context.tick());
            return strength >= min && strength <= max;
        }
    }

    /** Every operand must match; an empty list matches. */
    record All(List<TriggerCondition> operands) implements TriggerCondition {

        public All {
            operands = List.copyOf(operands);
        }

        @Override
        public String kind() {
            return "all";
        }

        @Override
        public boolean matches(TriggerContext context, MemoryRecord memory) {
            for (TriggerCondition operand : operands) {
                if (!operand.matches(context, memory)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Any operand may match; an empty list does not match. */
    record Any(List<TriggerCondition> operands) implements TriggerCondition {

        public Any {
            operands = List.copyOf(operands);
        }

        @Override
        public String kind() {
            return "any";
        }

        @Override
        public boolean matches(TriggerContext context, MemoryRecord memory) {
            for (TriggerCondition operand : operands) {
                if (operand.matches(context, memory)) {
                    return true;
                }
            }
            return false;
        }
    }

    record Not(TriggerCondition operand) implements TriggerCondition {

        @Override
        public String kind() {
            return "not";
        }

        @Override
        public boolean matches(TriggerContext context, MemoryRecord memory) {
            return !operand.matches(context, memory);
        }
    }
}
