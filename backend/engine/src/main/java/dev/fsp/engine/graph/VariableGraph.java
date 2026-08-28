package dev.fsp.engine.graph;

import dev.fsp.engine.spec.LinkSpec;
import dev.fsp.engine.spec.ObjectTypeSpec;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.spec.VariableRef;
import dev.fsp.engine.spec.VariableSpec;
import dev.fsp.engine.state.DelayLine;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The system-dynamics half of the engine: reads variables, pushes contributions along links, and
 * writes the results back.
 *
 * <p>Integration is explicit Euler with a one-tick step. Every link reads the value the source held
 * at the <em>start</em> of the tick, so the order links are evaluated in cannot change the outcome -
 * without that, a loop's behaviour would depend on the order the author happened to add its edges.
 */
public final class VariableGraph {

    private final SystemSpec spec;
    private final List<LinkSpec> links;
    private final Map<String, Double> previousReadings = new LinkedHashMap<>();

    public VariableGraph(SystemSpec spec) {
        this.spec = spec;
        this.links = spec.links();
    }

    /**
     * Advances every link by one tick and applies what arrives.
     *
     * @return the contribution delivered to each written variable, keyed by series key, for tracing
     */
    public Map<String, Double> propagate(SimulationState state) {
        // Phase 1: read. Snapshot every source before anything is written.
        Map<String, Double> readings = new LinkedHashMap<>();
        for (LinkSpec link : links) {
            readings.computeIfAbsent(link.source().seriesKey(), key -> read(state, link.source()));
        }

        // Phase 2: push each link's contribution into its delay line and collect what comes out.
        Map<String, Double> arriving = new LinkedHashMap<>();
        for (LinkSpec link : links) {
            double reading = readings.get(link.source().seriesKey());
            double input = link.usesRate() ? reading - previousReadings.getOrDefault(link.source().seriesKey(), reading)
                    : reading;
            DelayLine line = state.delayLine(link.id(), link.delayTicks());
            double delivered = line.advance(link.contribution(input));
            arriving.merge(link.target().seriesKey(), delivered, Double::sum);
        }

        // Phase 3: write. Auxiliaries are replaced by what arrived; stocks accumulate it.
        Map<String, Double> applied = new LinkedHashMap<>();
        for (LinkSpec link : links) {
            String key = link.target().seriesKey();
            if (applied.containsKey(key)) {
                continue;
            }
            double contribution = arriving.getOrDefault(key, 0.0);
            write(state, link.target(), contribution);
            applied.put(key, contribution);
        }

        previousReadings.clear();
        previousReadings.putAll(readings);
        return applied;
    }

    /** Current value behind a reference, resolving aggregates across the objects of a type. */
    public double read(SimulationState state, VariableRef ref) {
        return switch (ref) {
            case VariableRef.Global global -> state.hasGlobal(global.name()) ? state.global(global.name()) : 0.0;
            case VariableRef.OfObject ofObject -> state.hasObject(ofObject.objectId())
                    ? state.object(ofObject.objectId()).getOrDefault(ofObject.name(), 0.0)
                    : 0.0;
            case VariableRef.OfType ofType -> aggregate(state, ofType);
        };
    }

    private double aggregate(SimulationState state, VariableRef.OfType ref) {
        List<ObjectState> objects = state.objectsOfType(ref.typeId());
        if (objects.isEmpty()) {
            return 0.0;
        }
        List<Double> values = new ArrayList<>(objects.size());
        for (ObjectState object : objects) {
            values.add(object.getOrDefault(ref.name(), 0.0));
        }
        return switch (ref.aggregate()) {
            case MEAN -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            case SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
            case MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            case MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            case SPREAD -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0)
                    - values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            case VARIANCE -> variance(values);
        };
    }

    private static double variance(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double total = 0.0;
        for (double value : values) {
            total += (value - mean) * (value - mean);
        }
        return total / values.size();
    }

    /** Applies a contribution, honouring the target variable's kind and bounds. */
    private void write(SimulationState state, VariableRef ref, double contribution) {
        switch (ref) {
            case VariableRef.Global global -> {
                if (!spec.hasGlobalVariable(global.name())) {
                    return;
                }
                VariableSpec variable = spec.globalVariable(global.name());
                if (!variable.isWritable()) {
                    return;
                }
                double current = state.hasGlobal(global.name()) ? state.global(global.name()) : variable.initial();
                state.setGlobal(global.name(), variable.clamp(next(variable, current, contribution)));
            }
            case VariableRef.OfObject ofObject -> {
                if (!state.hasObject(ofObject.objectId())) {
                    return;
                }
                ObjectState object = state.object(ofObject.objectId());
                if (!spec.hasObjectType(object.typeId())) {
                    return;
                }
                ObjectTypeSpec type = spec.objectType(object.typeId());
                if (!type.hasVariable(ofObject.name())) {
                    return;
                }
                VariableSpec variable = type.variable(ofObject.name());
                if (!variable.isWritable()) {
                    return;
                }
                double current = object.getOrDefault(ofObject.name(), variable.initial());
                object.set(ofObject.name(), variable.clamp(next(variable, current, contribution)));
            }
            case VariableRef.OfType ignored -> {
                // Aggregates are read-only; the spec validator rejects links that target them.
            }
        }
    }

    private static double next(VariableSpec variable, double current, double contribution) {
        return variable.kind() == VariableSpec.Kind.AUXILIARY ? contribution : current + contribution;
    }

    /** Restores the rate-of-change baseline after a checkpoint restore. */
    public void primeRateBaseline(SimulationState state) {
        previousReadings.clear();
        for (LinkSpec link : links) {
            previousReadings.computeIfAbsent(link.source().seriesKey(), key -> read(state, link.source()));
        }
    }
}
