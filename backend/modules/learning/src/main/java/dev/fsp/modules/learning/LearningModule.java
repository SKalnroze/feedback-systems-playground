package dev.fsp.modules.learning;

import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.module.SimulationModule;
import dev.fsp.engine.spec.ObjectTypeSpec;
import java.util.List;

/**
 * A second domain pack, and the check that the module boundary is real.
 *
 * <p>Nothing in the engine knew about relationships; nothing in it knows about studying either.
 * This pack shares no code with the interpersonal one and models something structurally different -
 * memories about inert material rather than about other agents - which is the only way to find out
 * whether the extension point extends or merely exists.
 */
public final class LearningModule implements SimulationModule {

    public static final String ID = "learning";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Learning and forgetting";
    }

    @Override
    public String description() {
        return "Learners, topics, and what survives between studying something and being asked about it.";
    }

    @Override
    public List<ObjectTypeSpec> objectTypes() {
        return List.of(Learner.learnerType(), Learner.topicType());
    }

    @Override
    public List<InteractionRule> interactionRules() {
        return List.of(new StudyRule(), new TestRule(), new RestRule());
    }

    @Override
    public List<Preset> presets() {
        return LearningPresets.all();
    }
}
