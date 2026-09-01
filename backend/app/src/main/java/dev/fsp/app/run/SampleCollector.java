package dev.fsp.app.run;

import dev.fsp.engine.memory.MemoryRecord;
import dev.fsp.engine.spec.ObjectSpec;
import dev.fsp.engine.spec.ObjectTypeSpec;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.spec.VariableSpec;
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

    /**
     * Every value worth recording at this tick, keyed by series key.
     *
     * <p>Groups are recorded as statistics, not member by member. A population of two thousand
     * would otherwise produce twenty thousand series every tick, which no chart can draw and no
     * amount of database is worth spending on: the question a population answers is "how is the
     * group doing and how far apart are its members", and that is three numbers, not two thousand.
     * Individual members can still be sampled by setting {@code sampleGroupMembers}, which is for
     * following a handful of them in detail.
     */
    Map<String, Double> collect(SimulationState state) {
        Map<String, Double> values = new LinkedHashMap<>();

        state.globals().forEach((name, value) -> values.put("global." + name, value));

        int membersToSample = spec.settings().sampleGroupMembers();
        for (ObjectSpec authored : spec.objects()) {
            List<ObjectState> members = state.membersOf(authored.id());
            if (members.isEmpty()) {
                continue;
            }
            if (!authored.isGroup()) {
                ObjectState object = members.get(0);
                object.variables().forEach((name, value) -> values.put(object.id() + "." + name, value));
                collectMemoryReadings(state, object, values);
                continue;
            }
            collectGroupReadings(state, authored, members, values);
            for (int index = 0; index < Math.min(membersToSample, members.size()); index++) {
                ObjectState member = members.get(index);
                member.variables().forEach((name, value) -> values.put(member.id() + "." + name, value));
                collectMemoryReadings(state, member, values);
            }
        }

        values.put("system.memoryCount", (double) state.memories().size());
        values.put("system.memoriesForgotten", (double) state.memories().forgottenCount());
        return values;
    }

    /**
     * Mean, spread and extremes for each of a group's variables.
     *
     * <p>The spread is the one that earns its place. A mean alone cannot tell a group that is
     * uniformly mediocre from one that has split into a contented half and a resentful half, and
     * that difference is usually the finding.
     */
    private void collectGroupReadings(SimulationState state, ObjectSpec authored, List<ObjectState> members,
            Map<String, Double> values) {
        ObjectTypeSpec type = spec.objectType(authored.typeId());
        for (VariableSpec variable : type.variables()) {
            String name = variable.name();
            double total = 0.0;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (ObjectState member : members) {
                double value = member.getOrDefault(name, variable.initial());
                total += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            double mean = total / members.size();
            values.put(authored.id() + "." + name + ".mean", mean);
            values.put(authored.id() + "." + name + ".min", min);
            values.put(authored.id() + "." + name + ".max", max);
        }

        double totalMemories = 0.0;
        double totalStrength = 0.0;
        double weightedValence = 0.0;
        long tick = state.tick();
        for (ObjectState member : members) {
            List<MemoryRecord> memories = state.memories().of(member.id());
            totalMemories += memories.size();
            for (MemoryRecord memory : memories) {
                double strength = memory.strengthAt(tick);
                totalStrength += strength;
                weightedValence += strength * memory.valence();
            }
        }
        values.put(authored.id() + ".memoryCount.mean", totalMemories / members.size());
        values.put(authored.id() + ".memoryStrength.mean",
                totalMemories == 0.0 ? 0.0 : totalStrength / totalMemories);
        values.put(authored.id() + ".memoryValence.mean",
                totalStrength == 0.0 ? 0.0 : weightedValence / totalStrength);
        values.put(authored.id() + ".members", (double) members.size());
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
        // Contains rather than endsWith: a group's readings carry a statistic suffix, so the key
        // is "crowd.memoryStrength.mean" rather than "ana.memoryStrength".
        if (seriesKey.startsWith("system.") || seriesKey.contains(".memoryCount")
                || seriesKey.contains(".memoryStrength") || seriesKey.contains(".memoryValence")) {
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
