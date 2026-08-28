package dev.fsp.engine.expr;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Map-backed {@link EvalContext} for exercising expressions without a running simulation. */
final class MapEvalContext implements EvalContext {

    private final Map<Scope, Map<String, Double>> variables = new HashMap<>();
    private final Map<Scope, Set<String>> tags = new HashMap<>();
    private final Map<Scope, String> types = new HashMap<>();
    private final Map<MemoryAggregateQuery.Aggregate, Double> aggregates = new HashMap<>();
    private long tick;

    static MapEvalContext create() {
        return new MapEvalContext();
    }

    MapEvalContext at(long tick) {
        this.tick = tick;
        return this;
    }

    MapEvalContext with(Scope scope, String name, double value) {
        variables.computeIfAbsent(scope, key -> new HashMap<>()).put(name, value);
        return this;
    }

    MapEvalContext tagged(Scope scope, String... values) {
        tags.put(scope, Set.of(values));
        return this;
    }

    MapEvalContext typed(Scope scope, String typeId) {
        types.put(scope, typeId);
        return this;
    }

    MapEvalContext aggregating(MemoryAggregateQuery.Aggregate aggregate, double value) {
        aggregates.put(aggregate, value);
        return this;
    }

    @Override
    public long tick() {
        return tick;
    }

    @Override
    public double variable(Scope scope, String name) {
        Map<String, Double> scoped = variables.get(scope);
        if (scoped == null || !scoped.containsKey(name)) {
            throw new EvalException("unbound variable " + scope + "." + name);
        }
        return scoped.get(name);
    }

    @Override
    public Set<String> tags(Scope scope) {
        return tags.getOrDefault(scope, Set.of());
    }

    @Override
    public String typeId(Scope scope) {
        return types.get(scope);
    }

    @Override
    public double memoryAggregate(MemoryAggregateQuery query) {
        return aggregates.getOrDefault(query.aggregate(), 0.0);
    }
}
