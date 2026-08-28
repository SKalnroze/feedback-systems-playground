package dev.fsp.engine.expr;

import java.util.Set;

/**
 * Everything an expression is allowed to see. Narrow on purpose: expressions come from
 * user-authored specs, so they get a read-only keyhole onto simulation state and nothing else.
 */
public interface EvalContext {

    long tick();

    /**
     * Current value of a variable.
     *
     * @throws EvalException if the scope is not bound here or the variable does not exist
     */
    double variable(Scope scope, String name);

    /** Tags of the object in the given scope; empty when the scope is unbound. */
    Set<String> tags(Scope scope);

    /** Object-type id of the object in the given scope, or {@code null} for {@link Scope#GLOBAL}. */
    String typeId(Scope scope);

    /**
     * Aggregate over the memories held by the object in {@link Scope#SELF}, optionally restricted
     * to memories about {@link Scope#TARGET}.
     */
    double memoryAggregate(MemoryAggregateQuery query);

    /**
     * @param aggregate  which statistic to take
     * @param kind       memory kind filter, or {@code null} for all kinds
     * @param aboutTarget restrict to memories about the object bound to {@link Scope#TARGET}
     * @param minStrength ignore memories weaker than this
     */
    record MemoryAggregateQuery(Aggregate aggregate, String kind, boolean aboutTarget, double minStrength) {

        public enum Aggregate {
            COUNT,
            SUM_STRENGTH,
            MEAN_STRENGTH,
            MAX_STRENGTH,
            MEAN_VALENCE,
            SUM_VALENCE
        }
    }
}
