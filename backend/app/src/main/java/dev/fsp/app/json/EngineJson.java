package dev.fsp.app.json;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.fsp.engine.event.Effect;
import dev.fsp.engine.event.EventGenerator;
import dev.fsp.engine.event.TargetSelector;
import dev.fsp.engine.expr.NumExpr;
import dev.fsp.engine.expr.Predicate;
import dev.fsp.engine.graph.Transfer;
import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.memory.TriggerCondition;
import dev.fsp.engine.spec.VariableRef;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.jsontype.NamedType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teaches Jackson how to read and write the engine's sealed type hierarchies.
 *
 * <p>The engine deliberately carries no serialisation annotations - it should not care whether it
 * is driven from JSON, a test or a future CLI - so the mapping lives here instead. Each hierarchy
 * gets a {@code kind} discriminator whose values match the {@code kind()} the engine already
 * reports, which keeps the wire format, the editor palette and the engine in agreement.
 *
 * <p>Records serialise as exactly their components, so derived accessors like {@code seriesKey()}
 * stay out of the payload and nothing has to be marked ignorable.
 */
public final class EngineJson {

    private EngineJson() {
    }

    /** Applies every mixin and subtype registration to a mapper builder. */
    public static void configure(JsonMapper.Builder builder) {
        builder.addMixIn(DecayModel.class, Discriminated.class)
                .addMixIn(TriggerCondition.class, Discriminated.class)
                .addMixIn(EventGenerator.class, Discriminated.class)
                .addMixIn(TargetSelector.class, Discriminated.class)
                .addMixIn(Effect.class, Discriminated.class)
                .addMixIn(Transfer.class, Discriminated.class)
                .addMixIn(NumExpr.class, Discriminated.class)
                .addMixIn(Predicate.class, Discriminated.class)
                .addMixIn(VariableRef.class, Discriminated.class);

        subtypes().forEach((type, name) -> builder.registerSubtypes(new NamedType(type, name)));
    }

    /**
     * Every concrete variant, paired with the discriminator the engine reports for it.
     *
     * <p>Written out rather than derived reflectively: the names are part of the stored format, and
     * a rename that silently changes them would make previously saved systems unreadable.
     */
    public static Map<Class<?>, String> subtypes() {
        return Map.ofEntries(
                // Decay models
                Map.entry(DecayModel.NoDecay.class, "none"),
                Map.entry(DecayModel.Exponential.class, "exponential"),
                Map.entry(DecayModel.PowerLaw.class, "power-law"),
                Map.entry(DecayModel.Ebbinghaus.class, "ebbinghaus"),
                Map.entry(DecayModel.Linear.class, "linear"),
                Map.entry(DecayModel.Logistic.class, "logistic"),
                Map.entry(DecayModel.StepThreshold.class, "step-threshold"),
                Map.entry(DecayModel.ActRBaseLevel.class, "act-r-base-level"),

                // Reactivation triggers
                Map.entry(TriggerCondition.OnEvent.class, "on-event"),
                Map.entry(TriggerCondition.SubjectPresent.class, "subject-present"),
                Map.entry(TriggerCondition.OnCondition.class, "on-condition"),
                Map.entry(TriggerCondition.CueSimilarity.class, "cue-similarity"),
                Map.entry(TriggerCondition.Periodic.class, "trigger-periodic"),
                Map.entry(TriggerCondition.Stochastic.class, "stochastic"),
                Map.entry(TriggerCondition.StrengthBand.class, "strength-band"),
                Map.entry(TriggerCondition.All.class, "all"),
                Map.entry(TriggerCondition.Any.class, "any"),
                Map.entry(TriggerCondition.Not.class, "trigger-not"),

                // Event generators
                Map.entry(EventGenerator.Never.class, "never"),
                Map.entry(EventGenerator.FixedSchedule.class, "fixed-schedule"),
                Map.entry(EventGenerator.Bernoulli.class, "bernoulli"),
                Map.entry(EventGenerator.Poisson.class, "poisson"),
                Map.entry(EventGenerator.Periodic.class, "periodic"),
                Map.entry(EventGenerator.MarkovChain.class, "markov-chain"),
                Map.entry(EventGenerator.Burst.class, "burst"),

                // Target selectors
                Map.entry(TargetSelector.GlobalOnly.class, "global-target"),
                Map.entry(TargetSelector.Everyone.class, "everyone"),
                Map.entry(TargetSelector.RandomK.class, "random-k"),
                Map.entry(TargetSelector.Matching.class, "matching"),
                Map.entry(TargetSelector.Specific.class, "specific"),
                Map.entry(TargetSelector.RandomPair.class, "random-pair"),
                Map.entry(TargetSelector.NamedPair.class, "named-pair"),
                Map.entry(TargetSelector.AllPairs.class, "all-pairs"),

                // Effects
                Map.entry(Effect.SetVariable.class, "set-variable"),
                Map.entry(Effect.AdjustVariable.class, "adjust-variable"),
                Map.entry(Effect.InjectMemory.class, "inject-memory"),
                Map.entry(Effect.AddTag.class, "add-tag"),
                Map.entry(Effect.RemoveTag.class, "remove-tag"),
                Map.entry(Effect.EmitEvent.class, "emit-event"),
                Map.entry(Effect.SpawnObject.class, "spawn-object"),
                Map.entry(Effect.RemoveObject.class, "remove-object"),
                Map.entry(Effect.Conditional.class, "conditional"),
                Map.entry(Effect.ToBystanders.class, "to-bystanders"),

                // Transfer functions
                Map.entry(Transfer.Linear.class, "transfer-linear"),
                Map.entry(Transfer.Sigmoid.class, "sigmoid"),
                Map.entry(Transfer.Tanh.class, "tanh"),
                Map.entry(Transfer.Threshold.class, "threshold"),
                Map.entry(Transfer.Saturating.class, "saturating"),
                Map.entry(Transfer.Logarithmic.class, "logarithmic"),
                Map.entry(Transfer.PowerCurve.class, "power"),

                // Numeric expressions
                Map.entry(NumExpr.Constant.class, "const"),
                Map.entry(NumExpr.Var.class, "var"),
                Map.entry(NumExpr.Tick.class, "tick"),
                Map.entry(NumExpr.MemoryAggregate.class, "memory"),
                Map.entry(NumExpr.Arithmetic.class, "arith"),
                Map.entry(NumExpr.Unary.class, "unary"),
                Map.entry(NumExpr.Clamp.class, "clamp"),
                Map.entry(NumExpr.Conditional.class, "if"),
                Map.entry(NumExpr.Sum.class, "sum"),

                // Predicates
                Map.entry(Predicate.Constant.class, "always"),
                Map.entry(Predicate.Compare.class, "compare"),
                Map.entry(Predicate.And.class, "and"),
                Map.entry(Predicate.Or.class, "or"),
                Map.entry(Predicate.Not.class, "not"),
                Map.entry(Predicate.HasTag.class, "hasTag"),
                Map.entry(Predicate.IsType.class, "isType"),

                // Variable references
                Map.entry(VariableRef.Global.class, "global"),
                Map.entry(VariableRef.OfObject.class, "object"),
                Map.entry(VariableRef.OfType.class, "type-aggregate"));
    }

    /** Discriminator names grouped by hierarchy, for the editor's palette and schema generation. */
    public static Map<String, List<String>> kindsByHierarchy() {
        return Map.of("decayModel", kindsOf(DecayModel.class), "triggerCondition", kindsOf(TriggerCondition.class),
                "eventGenerator", kindsOf(EventGenerator.class), "targetSelector", kindsOf(TargetSelector.class),
                "effect", kindsOf(Effect.class), "transfer", kindsOf(Transfer.class), "numExpr",
                kindsOf(NumExpr.class), "predicate", kindsOf(Predicate.class), "variableRef",
                kindsOf(VariableRef.class));
    }

    private static List<String> kindsOf(Class<?> hierarchy) {
        return subtypes().entrySet().stream().filter(entry -> hierarchy.isAssignableFrom(entry.getKey()))
                .map(Map.Entry::getValue).sorted().toList();
    }

    /**
     * Mixin adding a {@code kind} discriminator. Subtype names are registered programmatically, so
     * {@link JsonSubTypes} is deliberately absent here.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    private abstract static class Discriminated {
    }
}
