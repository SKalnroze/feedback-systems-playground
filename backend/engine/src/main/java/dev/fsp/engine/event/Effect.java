package dev.fsp.engine.event;

import dev.fsp.engine.expr.NumExpr;
import dev.fsp.engine.expr.Predicate;
import dev.fsp.engine.expr.Scope;
import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.state.ObjectState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What an event does when it happens. */
public sealed interface Effect {

    String kind();

    void apply(EffectContext context, TargetSelector.Target target);

    /** Overwrites a variable with the value of an expression. */
    record SetVariable(Scope scope, String name, NumExpr value) implements Effect {

        @Override
        public String kind() {
            return "set-variable";
        }

        @Override
        public void apply(EffectContext context, TargetSelector.Target target) {
            double resolved = value.eval(context.evalContext());
            writeVariable(context, target, scope, name, resolved);
        }
    }

    /**
     * Moves a variable by a computed amount. The everyday effect: a favour adds to trust, an
     * argument subtracts from it.
     */
    record AdjustVariable(Scope scope, String name, NumExpr amount, Mode mode) implements Effect {

        public enum Mode {
            ADD,
            MULTIPLY
        }

        public static AdjustVariable add(Scope scope, String name, double delta) {
            return new AdjustVariable(scope, name, NumExpr.of(delta), Mode.ADD);
        }

        @Override
        public String kind() {
            return "adjust-variable";
        }

        @Override
        public void apply(EffectContext context, TargetSelector.Target target) {
            double delta = amount.eval(context.evalContext());
            double current = readVariable(context, target, scope, name);
            double updated = mode == Mode.ADD ? current + delta : current * delta;
            writeVariable(context, target, scope, name, updated);
        }
    }

    /**
     * Lays down a memory. The event happened to someone; this is what they will still know about
     * it later, and how strongly.
     *
     * @param ownerScope   who remembers
     * @param subjectScope who the memory is about
     * @param features     cue features, used later for similarity-based recall
     */
    record InjectMemory(Scope ownerScope, Scope subjectScope, String memoryKind, NumExpr strength, NumExpr valence,
            double salience, Map<String, Double> features, DecayModel decayOverride) implements Effect {

        public InjectMemory {
            features = Map.copyOf(features);
            if (memoryKind == null || memoryKind.isBlank()) {
                throw new IllegalArgumentException("memoryKind must not be blank");
            }
        }

        public static InjectMemory of(String memoryKind, double strength, double valence) {
            return new InjectMemory(Scope.SELF, Scope.TARGET, memoryKind, NumExpr.of(strength), NumExpr.of(valence),
                    0.5, Map.of(), null);
        }

        @Override
        public String kind() {
            return "inject-memory";
        }

        @Override
        public void apply(EffectContext context, TargetSelector.Target target) {
            ObjectState owner = objectFor(target, ownerScope);
            ObjectState subject = objectFor(target, subjectScope);
            if (owner == null || subject == null) {
                return;
            }
            context.injectMemory(owner, subject, memoryKind, strength.eval(context.evalContext()),
                    valence.eval(context.evalContext()), salience, features, decayOverride);
        }
    }

    /** Adds a tag, e.g. marking someone as having been through a restructuring. */
    record AddTag(Scope scope, String tag) implements Effect {

        @Override
        public String kind() {
            return "add-tag";
        }

        @Override
        public void apply(EffectContext context, TargetSelector.Target target) {
            ObjectState object = objectFor(target, scope);
            if (object != null) {
                object.tags().add(tag);
            }
        }
    }

    record RemoveTag(Scope scope, String tag) implements Effect {

        @Override
        public String kind() {
            return "remove-tag";
        }

        @Override
        public void apply(EffectContext context, TargetSelector.Target target) {
            ObjectState object = objectFor(target, scope);
            if (object != null) {
                object.tags().remove(tag);
            }
        }
    }

    /** Fires another event, letting one shock cascade into others. */
    record EmitEvent(String eventId) implements Effect {

        @Override
        public String kind() {
            return "emit-event";
        }

        @Override
        public void apply(EffectContext context, TargetSelector.Target target) {
            context.emitEvent(eventId);
        }
    }

    /** Introduces a new object, e.g. someone joining the team mid-run. */
    record SpawnObject(String typeId, Map<String, Double> initialVariables) implements Effect {

        public SpawnObject {
            initialVariables = Map.copyOf(initialVariables);
        }

        @Override
        public String kind() {
            return "spawn-object";
        }

        @Override
        public void apply(EffectContext context, TargetSelector.Target target) {
            ObjectState spawned = context.spawnObject(typeId);
            for (Map.Entry<String, Double> entry : initialVariables.entrySet()) {
                spawned.set(entry.getKey(), entry.getValue());
            }
            context.log("spawned " + spawned.id());
        }
    }

    /** Removes an object; everyone's memories of them go too. */
    record RemoveObject(Scope scope) implements Effect {

        @Override
        public String kind() {
            return "remove-object";
        }

        @Override
        public void apply(EffectContext context, TargetSelector.Target target) {
            ObjectState object = objectFor(target, scope);
            if (object != null) {
                context.state().removeObject(object.id());
                context.log("removed " + object.id());
            }
        }
    }

    /** Applies one branch or the other, so a single event can mean different things to different people. */
    record Conditional(Predicate condition, List<Effect> whenTrue, List<Effect> whenFalse) implements Effect {

        public Conditional {
            whenTrue = List.copyOf(whenTrue);
            whenFalse = List.copyOf(whenFalse);
        }

        @Override
        public String kind() {
            return "conditional";
        }

        @Override
        public void apply(EffectContext context, TargetSelector.Target target) {
            List<Effect> branch = condition.test(context.evalContext()) ? whenTrue : whenFalse;
            for (Effect effect : branch) {
                effect.apply(context, target);
            }
        }
    }

    /**
     * Applies effects to everyone who is not the target: the bystanders. Pairs with observation
     * memories to model reputational spillover from a single incident.
     */
    record ToBystanders(String typeId, List<Effect> effects, double probability) implements Effect {

        public ToBystanders {
            effects = List.copyOf(effects);
            if (probability < 0.0 || probability > 1.0) {
                throw new IllegalArgumentException("probability must be within [0, 1]: " + probability);
            }
        }

        @Override
        public String kind() {
            return "to-bystanders";
        }

        @Override
        public void apply(EffectContext context, TargetSelector.Target target) {
            List<ObjectState> pool = typeId == null ? context.state().activeObjects()
                    : context.state().objectsOfType(typeId);
            int index = 0;
            for (ObjectState bystander : pool) {
                index++;
                if (bystander == target.primary() || bystander == target.secondary()) {
                    continue;
                }
                if (!context.rng().derive(index).nextBoolean(probability)) {
                    continue;
                }
                // The bystander becomes SELF; the original actor stays as the thing observed.
                TargetSelector.Target observed = new TargetSelector.Target(bystander, target.primary());
                for (Effect effect : effects) {
                    effect.apply(context, observed);
                }
            }
        }
    }

    private static ObjectState objectFor(TargetSelector.Target target, Scope scope) {
        return switch (scope) {
            case SELF -> target.primary();
            case TARGET -> target.secondary();
            case GLOBAL -> null;
        };
    }

    private static double readVariable(EffectContext context, TargetSelector.Target target, Scope scope, String name) {
        if (scope == Scope.GLOBAL) {
            return context.state().hasGlobal(name) ? context.state().global(name) : 0.0;
        }
        ObjectState object = objectFor(target, scope);
        return object == null ? 0.0 : object.getOrDefault(name, 0.0);
    }

    private static void writeVariable(EffectContext context, TargetSelector.Target target, Scope scope, String name,
            double value) {
        if (scope == Scope.GLOBAL) {
            context.state().setGlobal(name, value);
            return;
        }
        ObjectState object = objectFor(target, scope);
        if (object != null) {
            object.set(name, value);
        }
    }

    /** Convenience for building a feature map in spec code and tests. */
    static Map<String, Double> features(String... names) {
        Map<String, Double> features = new LinkedHashMap<>();
        for (String name : names) {
            features.put(name, 1.0);
        }
        return features;
    }
}
