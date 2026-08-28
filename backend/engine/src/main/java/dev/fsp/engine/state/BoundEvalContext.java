package dev.fsp.engine.state;

import dev.fsp.engine.expr.EvalContext;
import dev.fsp.engine.expr.EvalException;
import dev.fsp.engine.expr.Scope;
import java.util.Set;

/**
 * Binds {@link Scope#SELF} and {@link Scope#TARGET} to concrete objects so authored expressions can
 * be evaluated against live state.
 *
 * <p>Either binding may be absent - a global event has no self, a solitary rule has no target - in
 * which case referring to it is an authoring error and is reported as one rather than silently
 * evaluating to zero.
 */
public final class BoundEvalContext implements EvalContext {

    private final SimulationState state;
    private final ObjectState self;
    private final ObjectState target;

    private BoundEvalContext(SimulationState state, ObjectState self, ObjectState target) {
        this.state = state;
        this.self = self;
        this.target = target;
    }

    public static BoundEvalContext of(SimulationState state, ObjectState self, ObjectState target) {
        return new BoundEvalContext(state, self, target);
    }

    public static BoundEvalContext forObject(SimulationState state, ObjectState self) {
        return new BoundEvalContext(state, self, null);
    }

    public static BoundEvalContext globalOnly(SimulationState state) {
        return new BoundEvalContext(state, null, null);
    }

    public ObjectState self() {
        return self;
    }

    public ObjectState target() {
        return target;
    }

    @Override
    public long tick() {
        return state.tick();
    }

    @Override
    public double variable(Scope scope, String name) {
        if (scope == Scope.GLOBAL) {
            if (!state.hasGlobal(name)) {
                throw new EvalException("no such global variable: " + name);
            }
            return state.global(name);
        }
        ObjectState object = objectFor(scope);
        if (!object.has(name)) {
            throw new EvalException("object " + object.id() + " has no variable " + name);
        }
        return object.get(name);
    }

    @Override
    public Set<String> tags(Scope scope) {
        if (scope == Scope.GLOBAL) {
            return Set.of();
        }
        ObjectState object = scope == Scope.SELF ? self : target;
        return object == null ? Set.of() : Set.copyOf(object.tags());
    }

    @Override
    public String typeId(Scope scope) {
        if (scope == Scope.GLOBAL) {
            return null;
        }
        ObjectState object = scope == Scope.SELF ? self : target;
        return object == null ? null : object.typeId();
    }

    @Override
    public double memoryAggregate(MemoryAggregateQuery query) {
        if (self == null) {
            throw new EvalException("memory aggregates need a bound SELF object");
        }
        String subjectId = null;
        if (query.aboutTarget()) {
            if (target == null) {
                throw new EvalException("memory aggregate asked about TARGET but none is bound");
            }
            subjectId = target.id();
        }
        return state.memories().aggregate(self.id(), subjectId, state.tick(), query);
    }

    private ObjectState objectFor(Scope scope) {
        ObjectState object = scope == Scope.SELF ? self : target;
        if (object == null) {
            throw new EvalException("expression refers to " + scope + " but no object is bound there");
        }
        return object;
    }
}
