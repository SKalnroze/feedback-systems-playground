package dev.fsp.modules.learning;

import dev.fsp.engine.module.InteractionContext;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;
import dev.fsp.engine.state.ObjectState;
import java.util.List;
import java.util.Map;

/**
 * A learner sits down with a topic and lays down a trace.
 *
 * <p>Which topic gets studied is the interesting decision, and it is deliberately not "the weakest
 * one". People study what feels productive, and what feels productive is material that is already
 * familiar - the trap the module exists to show. A learner left to their own preference restudies
 * what they already know while the material they are about to forget goes untouched.
 */
public final class StudyRule implements InteractionRule {

    public static final String ID = "study";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Study a topic";
    }

    @Override
    public String description() {
        return "Learners pick a topic and study it, preferring what already feels familiar.";
    }

    @Override
    public List<Parameter> parameters() {
        return List.of(new Parameter("sessionsPerTick", "Study sessions per tick", 1.0, 0.0, 10.0),
                new Parameter("attentionCost", "Attention spent per session", 0.25, 0.0, 1.0),
                new Parameter("familiarityBias", "Preference for already-familiar material", 0.7, 0.0, 1.0),
                new Parameter("strength", "Strength of a fresh trace", 0.75, 0.0, 1.0));
    }

    @Override
    public void execute(InteractionContext context) {
        int sessions = (int) Math.round(context.param("sessionsPerTick", 1.0));
        double attentionCost = context.param("attentionCost", 0.25);
        double familiarityBias = context.param("familiarityBias", 0.7);
        double strength = context.param("strength", 0.75);

        List<ObjectState> learners = Topics.of(context, Learner.TYPE_ID);
        List<ObjectState> topics = Topics.of(context, Learner.TOPIC_TYPE_ID);
        if (learners.isEmpty() || topics.isEmpty()) {
            return;
        }

        for (int session = 0; session < sessions; session++) {
            for (ObjectState learner : learners) {
                double attention = learner.getOrDefault(Learner.ATTENTION, 1.0);
                double motivation = learner.getOrDefault(Learner.MOTIVATION, 0.5);
                if (attention < attentionCost || motivation < 0.1) {
                    continue;
                }
                Rng rng = context.rng(RngStream.INTERACTION_SELECTION).derive(session).derive(learner.id().hashCode());
                ObjectState topic = Topics.choose(context, learner, topics, familiarityBias, rng);
                if (topic == null) {
                    continue;
                }

                double difficulty = topic.getOrDefault(Learner.DIFFICULTY, 0.5);
                double aptitude = learner.getOrDefault(Learner.APTITUDE, 0.5);
                double quality = Math.clamp(strength * (0.5 + aptitude) * (1.0 - 0.5 * difficulty)
                        * (0.5 + 0.5 * attention), 0.05, 1.0);

                context.addCue("study", 1.0);
                context.markInPlay(learner);
                context.markInPlay(topic);
                // A study session is a memory the learner holds about the topic. Reviewing it later
                // is a reactivation of this trace rather than a new one, which is what lets the
                // decay model express spacing.
                context.injectMemory(learner, topic, "study", quality, 0.2, 0.6,
                        Map.of("study", 1.0, "difficulty", difficulty), null);

                learner.set(Learner.ATTENTION, Math.max(0.0, attention - attentionCost));
                // Confidence rises with exposure, not with retention. That gap is the whole point.
                learner.set(Learner.CONFIDENCE,
                        Math.clamp(learner.getOrDefault(Learner.CONFIDENCE, 0.3) + 0.05 * quality, 0.0, 1.0));
                context.log(learner.id() + " studied " + topic.id());
            }
        }
    }
}
