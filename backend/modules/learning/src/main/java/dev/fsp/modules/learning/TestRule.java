package dev.fsp.modules.learning;

import dev.fsp.engine.module.InteractionContext;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;
import dev.fsp.engine.state.ObjectState;
import java.util.List;
import java.util.Map;

/**
 * Being tested, which is the only thing here that measures rather than assumes.
 *
 * <p>A test does two separate things, and keeping them separate is the point. It reveals what the
 * learner can actually recall, which moves attainment and corrects confidence; and the act of
 * recalling successfully is itself a reactivation, so being tested strengthens the material more
 * than rereading it would. Confidence, by contrast, has been rising on exposure alone - so a first
 * test after a long cram is usually where the two numbers separate sharply.
 */
public final class TestRule implements InteractionRule {

    public static final String ID = "test";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Sit a test";
    }

    @Override
    public String description() {
        return "Measures what can actually be recalled, and strengthens it by recalling it.";
    }

    @Override
    public List<Parameter> parameters() {
        return List.of(new Parameter("probability", "Chance of a test each tick", 0.05, 0.0, 1.0),
                new Parameter("topicsPerTest", "Topics covered per test", 3.0, 1.0, 20.0),
                new Parameter("passMark", "Recall needed to count as known", 0.4, 0.0, 1.0),
                new Parameter("confidenceCorrection", "How sharply a result corrects belief", 0.5, 0.0, 1.0));
    }

    @Override
    public void execute(InteractionContext context) {
        double probability = context.param("probability", 0.05);
        Rng gate = context.rng(RngStream.INTERACTION_SELECTION).derive(7_001L);
        if (gate.nextDouble() >= probability) {
            return;
        }

        int topicsPerTest = (int) Math.round(context.param("topicsPerTest", 3.0));
        double passMark = context.param("passMark", 0.4);
        double correction = context.param("confidenceCorrection", 0.5);

        List<ObjectState> learners = Topics.of(context, Learner.TYPE_ID);
        List<ObjectState> topics = Topics.of(context, Learner.TOPIC_TYPE_ID);
        if (learners.isEmpty() || topics.isEmpty()) {
            return;
        }

        context.addCue("test", 1.0);
        for (ObjectState learner : learners) {
            int examined = Math.min(topicsPerTest, topics.size());
            double passed = 0.0;
            for (int i = 0; i < examined; i++) {
                ObjectState topic = topics.get(i);
                double recall = Topics.recall(context, learner, topic);
                context.markInPlay(learner);
                context.markInPlay(topic);
                if (recall >= passMark) {
                    passed++;
                    // Successful retrieval is itself a rehearsal, and a stronger one than rereading.
                    context.injectMemory(learner, topic, "recall", Math.min(1.0, recall + 0.15), 0.4, 0.9,
                            Map.of("test", 1.0, "study", 0.5), null);
                }
            }
            double score = examined == 0 ? 0.0 : passed / examined;
            double confidence = learner.getOrDefault(Learner.CONFIDENCE, 0.3);

            learner.set(Learner.ATTAINMENT,
                    Math.clamp(0.7 * learner.getOrDefault(Learner.ATTAINMENT, 0.0) + 0.3 * score, 0.0, 1.0));
            // Belief moves towards the measurement rather than jumping to it: one bad test does not
            // convince anyone they know nothing.
            learner.set(Learner.CONFIDENCE, Math.clamp(confidence + correction * (score - confidence), 0.0, 1.0));
            // Motivation follows the surprise, not the score. Doing better than you believed is
            // encouraging; doing worse is what makes people stop.
            double surprise = score - confidence;
            learner.set(Learner.MOTIVATION,
                    Math.clamp(learner.getOrDefault(Learner.MOTIVATION, 0.6) + 0.4 * surprise, 0.0, 1.0));
            context.log(learner.id() + " scored " + Math.round(score * 100) + "% (believed "
                    + Math.round(confidence * 100) + "%)");
        }
    }
}
