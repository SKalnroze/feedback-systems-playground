package dev.fsp.app.run;

import dev.fsp.engine.memory.MemoryRecord;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns simulation state into the numbers a chart can draw.
 *
 * <p>Records object and global variables, and three memory readings per object. Those three exist
 * because the interesting question in this tool is usually not "what is Alice's trust" but "how
 * much does Alice still remember, and how does she feel about it" - which is what actually drives
 * trust in the next tick.
 */
final class SampleCollector {

    /** Series categories, used by the UI to group the series picker. */
    static final String CATEGORY_VARIABLE = "variable";
    static final String CATEGORY_GLOBAL = "global";
    static final String CATEGORY_MEMORY = "memory";

    private final SystemSpec spec;

    SampleCollector(SystemSpec spec) {
        this.spec = spec;
    }

    /** Every value worth recording at this tick, keyed by series key. */
    Map<String, Double> collect(SimulationState state) {
        Map<String, Double> values = new LinkedHashMap<>();

        state.globals().forEach((name, value) -> values.put("global." + name, value));

        for (ObjectState object : state.allObjects()) {
            object.variables().forEach((name, value) -> values.put(object.id() + "." + name, value));
            collectMemoryReadings(state, object, values);
        }

        values.put("system.memoryCount", (double) state.memories().size());
        values.put("system.memoriesForgotten", (double) state.memories().forgottenCount());
        return values;
    }

    private void collectMemoryReadings(SimulationState state, ObjectState object, Map<String, Double> values) {
        List<MemoryRecord> memories = state.memories().of(object.id());
        if (memories.isEmpty()) {
            values.put(object.id() + ".memoryCount", 0.0);
            values.put(object.id() + ".memoryStrength", 0.0);
            values.put(object.id() + ".memoryValence", 0.0);
            return;
        }
        double totalStrength = 0.0;
        double weightedValence = 0.0;
        long tick = state.tick();
        for (MemoryRecord memory : memories) {
            double strength = memory.strengthAt(tick);
            totalStrength += strength;
            weightedValence += strength * memory.valence();
        }
        values.put(object.id() + ".memoryCount", (double) memories.size());
        values.put(object.id() + ".memoryStrength", totalStrength / memories.size());
        // Weighted by strength: a vivid grievance colours judgement more than a dozen faint ones.
        values.put(object.id() + ".memoryValence", totalStrength == 0.0 ? 0.0 : weightedValence / totalStrength);
    }

    /** Which group a series key belongs to, for the picker. */
    static String categoryOf(String seriesKey) {
        if (seriesKey.startsWith("global.")) {
            return CATEGORY_GLOBAL;
        }
        if (seriesKey.startsWith("system.") || seriesKey.endsWith(".memoryCount")
                || seriesKey.endsWith(".memoryStrength") || seriesKey.endsWith(".memoryValence")) {
            return CATEGORY_MEMORY;
        }
        return CATEGORY_VARIABLE;
    }

    /** Object id part of a series key, or null for global and system-wide series. */
    static String objectOf(String seriesKey) {
        int dot = seriesKey.indexOf('.');
        if (dot < 0) {
            return null;
        }
        String prefix = seriesKey.substring(0, dot);
        return prefix.equals("global") || prefix.equals("system") ? null : prefix;
    }

    /** Variable part of a series key. */
    static String variableOf(String seriesKey) {
        int dot = seriesKey.indexOf('.');
        return dot < 0 ? seriesKey : seriesKey.substring(dot + 1);
    }

    SystemSpec spec() {
        return spec;
    }
}
