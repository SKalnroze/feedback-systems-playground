package dev.fsp.modules.learning;

import dev.fsp.engine.module.InteractionContext;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.state.ObjectState;
import java.util.List;

/**
 * Time away from the material.
 *
 * <p>Attention recovers here and nowhere else, which is what makes cramming self-limiting: a
 * schedule that studies every tick never recovers the attention that makes studying effective, so
 * the traces it lays down are weak ones. Without this rule, more study would always be better and
 * the module would have nothing to say.
 */
public final class RestRule implements InteractionRule {

    public static final String ID = "rest";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Rest";
    }

    @Override
    public String description() {
        return "Attention recovers when it is not being spent.";
    }

    @Override
    public List<Parameter> parameters() {
        return List.of(new Parameter("recoveryPerTick", "Attention recovered per tick", 0.18, 0.0, 1.0),
                new Parameter("motivationDrift", "Drift back towards baseline motivation", 0.02, 0.0, 1.0));
    }

    @Override
    public void execute(InteractionContext context) {
        double recovery = context.param("recoveryPerTick", 0.18);
        double drift = context.param("motivationDrift", 0.02);

        for (ObjectState learner : Topics.of(context, Learner.TYPE_ID)) {
            learner.set(Learner.ATTENTION,
                    Math.clamp(learner.getOrDefault(Learner.ATTENTION, 1.0) + recovery, 0.0, 1.0));
            double motivation = learner.getOrDefault(Learner.MOTIVATION, 0.6);
            learner.set(Learner.MOTIVATION, Math.clamp(motivation + drift * (0.6 - motivation), 0.0, 1.0));
        }
    }
}
