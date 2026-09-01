package dev.fsp.engine.graph;

import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;
import dev.fsp.engine.spec.Coupling;
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
     * <p>Contributions accumulate per <em>target instance</em>, not per link. A link pointing at a
     * group of two thousand delivers two thousand separate writes, and two links pointing at the
     * same member have to sum rather than one of them silently winning.
     *
     * @return the contribution delivered to each written variable, keyed by series key, for tracing
     */
    public Map<String, Double> propagate(SimulationState state) {
        // Phase 1: read. Snapshot every source instance before anything is written.
        Map<String, Double> readings = new LinkedHashMap<>();
        for (LinkSpec link : links) {
            for (String key : sourceKeys(state, link)) {
                readings.computeIfAbsent(key, existing -> readKey(state, link.source(), existing));
            }
        }

        // Phase 2: expand each link over its endpoints' members, then push through its delay line.
        Map<String, Double> arriving = new LinkedHashMap<>();
        Map<String, VariableRef> targets = new LinkedHashMap<>();
        for (LinkSpec link : links) {
            Map<String, Double> perTarget = new LinkedHashMap<>();
            expand(state, link, readings, perTarget, targets);
            for (Map.Entry<String, Double> entry : perTarget.entrySet()) {
                // One delay line per link and target. A single target keeps the bare link id, so a
                // run checkpointed before groups existed still finds its delay state on resume.
                boolean single = entry.getKey().equals(link.target().seriesKey());
                DelayLine line = state.delayLine(single ? link.id() : link.id() + "@" + entry.getKey(),
                        link.delayTicks());
                arriving.merge(entry.getKey(), line.advance(entry.getValue()), Double::sum);
            }
        }

        // Phase 3: write. Auxiliaries are replaced by what arrived; stocks accumulate it.
        Map<String, Double> applied = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : arriving.entrySet()) {
            VariableRef ref = targets.get(entry.getKey());
            if (ref == null) {
                continue;
            }
            write(state, ref, entry.getValue());
            applied.put(entry.getKey(), entry.getValue());
        }

        previousReadings.clear();
        previousReadings.putAll(readings);
        return applied;
    }

    /**
     * Spreads one link across the members of its endpoints according to its coupling.
     *
     * <p>Everything written into {@code perTarget} is keyed by the concrete instance's series key,
     * which is what lets several source members fan into one target and sum properly.
     */
    private void expand(SimulationState state, LinkSpec link, Map<String, Double> readings,
            Map<String, Double> perTarget, Map<String, VariableRef> targets) {
        List<ObjectState> sourceMembers = membersFor(state, link.source());
        List<ObjectState> targetMembers = membersFor(state, link.target());
        int sourceCount = sourceMembers.isEmpty() ? 1 : sourceMembers.size();
        int targetCount = targetMembers.isEmpty() ? 1 : targetMembers.size();
        Coupling coupling = link.coupling().resolve(sourceCount, targetCount);

        switch (coupling.mode()) {
            case ONE_TO_ONE -> {
                // Pair by position over however many both ends actually have. Unequal sizes are a
                // validation error; at run time the surplus members are left unconnected rather
                // than silently wrapped around.
                int paired = Math.min(sourceCount, targetCount);
                for (int index = 0; index < paired; index++) {
                    deliver(link, readings, sourceMembers, index, targetMembers, index, perTarget, targets);
                }
            }
            case ONE_TO_MANY -> {
                for (int target = 0; target < targetCount; target++) {
                    deliver(link, readings, sourceMembers, 0, targetMembers, target, perTarget, targets);
                }
            }
            case MANY_TO_ONE -> {
                double reduced = reduce(coupling, sourceValues(link, readings, sourceMembers));
                add(link, reduced, targetMembers, 0, perTarget, targets);
            }
            case MANY_TO_MANY_AGGREGATE -> {
                double reduced = reduce(coupling, sourceValues(link, readings, sourceMembers));
                for (int target = 0; target < targetCount; target++) {
                    add(link, reduced, targetMembers, target, perTarget, targets);
                }
            }
            case MANY_TO_MANY_RANDOM -> {
                for (int target = 0; target < targetCount; target++) {
                    // Seeded by the member, so a given target is influenced by the same source at
                    // the same tick on every replay of the run.
                    Rng rng = Rng.of(state.runSeed(), state.tick(), RngStream.LINK_COUPLING,
                            link.id().hashCode()).derive(target);
                    deliver(link, readings, sourceMembers, rng.nextInt(sourceCount), targetMembers, target,
                            perTarget, targets);
                }
            }
            case MANY_TO_MANY_ALL -> {
                if ((long) sourceCount * targetCount > Coupling.MAX_PAIRS) {
                    // The validator rejects this, but objects can also be added while a run is
                    // going. Doing nothing beats stalling the tick loop.
                    return;
                }
                for (int source = 0; source < sourceCount; source++) {
                    for (int target = 0; target < targetCount; target++) {
                        deliver(link, readings, sourceMembers, source, targetMembers, target, perTarget, targets);
                    }
                }
            }
            case AUTO -> {
                // resolve() never returns AUTO.
            }
        }
    }

    private void deliver(LinkSpec link, Map<String, Double> readings, List<ObjectState> sourceMembers,
            int sourceIndex, List<ObjectState> targetMembers, int targetIndex, Map<String, Double> perTarget,
            Map<String, VariableRef> targets) {
        String key = sourceKey(link.source(), sourceMembers, sourceIndex);
        double reading = readings.getOrDefault(key, 0.0);
        double input = link.usesRate() ? reading - previousReadings.getOrDefault(key, reading) : reading;
        add(link, input, targetMembers, targetIndex, perTarget, targets);
    }

    private void add(LinkSpec link, double input, List<ObjectState> targetMembers, int targetIndex,
            Map<String, Double> perTarget, Map<String, VariableRef> targets) {
        VariableRef ref = targetRef(link.target(), targetMembers, targetIndex);
        if (ref == null) {
            return;
        }
        String key = ref.seriesKey();
        targets.putIfAbsent(key, ref);
        perTarget.merge(key, link.contribution(input), Double::sum);
    }

    private List<Double> sourceValues(LinkSpec link, Map<String, Double> readings, List<ObjectState> members) {
        if (members.isEmpty()) {
            return List.of(readings.getOrDefault(link.source().seriesKey(), 0.0));
        }
        List<Double> values = new ArrayList<>(members.size());
        for (int index = 0; index < members.size(); index++) {
            values.add(readings.getOrDefault(sourceKey(link.source(), members, index), 0.0));
        }
        return values;
    }

    private static double reduce(Coupling coupling, List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return switch (coupling.aggregate()) {
            case MEAN -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            case SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
            case MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            case MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            case SPREAD -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0)
                    - values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            case VARIANCE -> variance(values);
        };
    }

    /** Members behind a reference, or empty when it does not point at an object at all. */
    private static List<ObjectState> membersFor(SimulationState state, VariableRef ref) {
        if (!(ref instanceof VariableRef.OfObject ofObject)) {
            return List.of();
        }
        List<ObjectState> members = state.membersOf(ofObject.objectId());
        if (!members.isEmpty()) {
            return members;
        }
        // The reference may name one member of a group directly, which is how an author singles a
        // single instance out of a population.
        return state.hasObject(ofObject.objectId()) ? List.of(state.object(ofObject.objectId())) : List.of();
    }

    private static List<String> sourceKeys(SimulationState state, LinkSpec link) {
        List<ObjectState> members = membersFor(state, link.source());
        if (members.isEmpty()) {
            return List.of(link.source().seriesKey());
        }
        List<String> keys = new ArrayList<>(members.size());
        for (int index = 0; index < members.size(); index++) {
            keys.add(sourceKey(link.source(), members, index));
        }
        return keys;
    }

    private static String sourceKey(VariableRef ref, List<ObjectState> members, int index) {
        if (members.isEmpty() || !(ref instanceof VariableRef.OfObject ofObject)) {
            return ref.seriesKey();
        }
        ObjectState member = members.get(Math.min(index, members.size() - 1));
        return member.id() + "." + ofObject.name();
    }

    private static VariableRef targetRef(VariableRef ref, List<ObjectState> members, int index) {
        if (members.isEmpty() || !(ref instanceof VariableRef.OfObject ofObject)) {
            return ref;
        }
        if (index >= members.size()) {
            return null;
        }
        return new VariableRef.OfObject(members.get(index).id(), ofObject.name());
    }

    /** Reads one concrete instance key, falling back to the reference for non-object sources. */
    private double readKey(SimulationState state, VariableRef ref, String key) {
        if (ref instanceof VariableRef.OfObject ofObject) {
            int dot = key.lastIndexOf('.');
            String objectId = dot < 0 ? ofObject.objectId() : key.substring(0, dot);
            return state.hasObject(objectId) ? state.object(objectId).getOrDefault(ofObject.name(), 0.0) : 0.0;
        }
        return read(state, ref);
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
            for (String key : sourceKeys(state, link)) {
                previousReadings.computeIfAbsent(key, existing -> readKey(state, link.source(), existing));
            }
        }
    }
}
