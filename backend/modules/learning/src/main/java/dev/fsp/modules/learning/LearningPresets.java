package dev.fsp.modules.learning;

import dev.fsp.engine.event.Effect;
import dev.fsp.engine.event.EventGenerator;
import dev.fsp.engine.event.TargetSelector;
import dev.fsp.engine.expr.NumExpr;
import dev.fsp.engine.expr.Scope;
import dev.fsp.engine.graph.Transfer;
import dev.fsp.engine.memory.MemoryTrigger;
import dev.fsp.engine.memory.MemoryTrigger.MemoryFilter;
import dev.fsp.engine.memory.MemoryTrigger.ReactivationEffect;
import dev.fsp.engine.memory.TriggerCondition;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.module.SimulationModule.Preset;
import dev.fsp.engine.spec.EventSpec;
import dev.fsp.engine.spec.LinkSpec;
import dev.fsp.engine.spec.ObjectSpec;
import dev.fsp.engine.spec.SimulationSettings;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.spec.VariableRef;
import dev.fsp.engine.spec.VariableSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two ways of preparing for the same exam.
 *
 * <p>A matched pair on purpose: same learner, same topics, different distribution of the same
 * effort. Run both and the spacing effect shows up as a divergence between two lines rather than as
 * an assertion in a comment, which is the sort of thing this tool is for.
 */
public final class LearningPresets {

    private LearningPresets() {
    }

    public static List<Preset> all() {
        return List.of(spacedPractice(), examCram());
    }

    private static ObjectSpec learner(String id, double aptitude) {
        return new ObjectSpec(id, Learner.TYPE_ID, id,
                Map.of(Learner.APTITUDE, aptitude, Learner.MOTIVATION, 0.6), Set.of(), Map.of());
    }

    private static ObjectSpec topic(String id, double difficulty) {
        return new ObjectSpec(id, Learner.TOPIC_TYPE_ID, id, Map.of(Learner.DIFFICULTY, difficulty), Set.of(),
                Map.of());
    }

    /**
     * Steady study with frequent low-stakes tests.
     *
     * <p>Testing is set high deliberately. Each successful recall reactivates the trace, and because
     * the decay model consolidates on reactivation, the material half-life grows as the run goes on.
     * Attainment should climb and keep climbing, with belief tracking it closely.
     */
    public static Preset spacedPractice() {
        SystemSpec spec = SystemSpec.builder("spaced-practice").name("Spaced practice")
                .description("Study a little each day, test often, let the material consolidate.")
                .module(LearningModule.ID)
                .objectType(Learner.learnerType()).objectType(Learner.topicType())
                .object(learner("sam", 0.5))
                .object(topic("algebra", 0.5)).object(topic("geometry", 0.6)).object(topic("calculus", 0.8))
                .object(topic("statistics", 0.55)).object(topic("proofs", 0.7))
                .globalVariable(new VariableSpec("examPressure", "Exam pressure", VariableSpec.Kind.STOCK, 0.1, 0.0,
                        1.0))
                // Pressure lifts motivation a little, and only a little: it is not the lever this
                // preset is about.
                .link(new LinkSpec("pressure-to-motivation", "Pressure lifts motivation",
                        new VariableRef.Global("examPressure"),
                        new VariableRef.OfObject("sam", Learner.MOTIVATION), 0.02, 0, Transfer.linear(), false))
                // Being tested on something still recallable is what makes it durable.
                .trigger(new MemoryTrigger("retrieval-strengthens", "Retrieval strengthens",
                        new MemoryFilter(Set.of("study", "recall"), 0.05, 1.0, 2L),
                        new TriggerCondition.All(List.of(TriggerCondition.SubjectPresent.INSTANCE,
                                new TriggerCondition.CueSimilarity(0.5))),
                        new ReactivationEffect(0.06, 2.2, true, 0.01, null, 0.0, null), 5))
                .interaction(InteractionRule.Config.enabled(StudyRule.ID).with("sessionsPerTick", 1.0)
                        .with("familiarityBias", 0.35))
                .interaction(InteractionRule.Config.enabled(TestRule.ID).with("probability", 0.2)
                        .with("topicsPerTest", 5.0))
                .interaction(InteractionRule.Config.enabled(RestRule.ID))
                .settings(new SimulationSettings(1, 250, 0L, 1, false)).build();

        return new Preset("spaced-practice", "Spaced practice",
                "One learner, five topics, frequent testing. Attainment and confidence stay together.", spec);
    }

    /**
     * Nothing until the deadline, then everything at once.
     *
     * <p>Same learner and topics as the spaced preset. Study is heavy but attention never recovers
     * between sessions, and the familiarity bias is high, so the effort concentrates on whatever
     * already feels known. Confidence should run well ahead of attainment right up to the exam.
     */
    public static Preset examCram() {
        SystemSpec spec = SystemSpec.builder("exam-cram").name("Exam cram")
                .description("No work, then a great deal of it at once, on the material that feels easiest.")
                .module(LearningModule.ID)
                .objectType(Learner.learnerType()).objectType(Learner.topicType())
                .object(learner("sam", 0.5))
                .object(topic("algebra", 0.5)).object(topic("geometry", 0.6)).object(topic("calculus", 0.8))
                .object(topic("statistics", 0.55)).object(topic("proofs", 0.7))
                .globalVariable(new VariableSpec("examPressure", "Exam pressure", VariableSpec.Kind.STOCK, 0.0, 0.0,
                        1.0))
                .event(EventSpec
                        .of("deadline-looms", EventGenerator.FixedSchedule.at(150L),
                                TargetSelector.GlobalOnly.INSTANCE,
                                List.of(new Effect.SetVariable(Scope.GLOBAL, "examPressure", NumExpr.of(1.0))))
                        .limitedTo(1))
                .event(EventSpec.of("all-nighter", new EventGenerator.Periodic(6, 2, 152),
                        new TargetSelector.Everyone(Learner.TYPE_ID),
                        List.of(Effect.AdjustVariable.add(Scope.SELF, Learner.ATTENTION, -0.3),
                                Effect.AdjustVariable.add(Scope.SELF, Learner.CONFIDENCE, 0.06))))
                .interaction(InteractionRule.Config.enabled(StudyRule.ID).with("sessionsPerTick", 3.0)
                        .with("familiarityBias", 0.9).with("attentionCost", 0.4))
                // Barely any testing, so nothing corrects the belief until it is too late.
                .interaction(InteractionRule.Config.enabled(TestRule.ID).with("probability", 0.02))
                .interaction(InteractionRule.Config.enabled(RestRule.ID).with("recoveryPerTick", 0.08))
                .settings(new SimulationSettings(1, 250, 0L, 1, false)).build();

        return new Preset("exam-cram", "Exam cram",
                "The same learner and material, all of it left to the last minute.", spec);
    }
}
