package dev.fsp.engine.spec;

import dev.fsp.engine.memory.MemorySettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A kind of object: what variables its instances carry, and how they remember.
 *
 * @param defaultFeatures cue features every instance starts with, on top of its own
 */
public record ObjectTypeSpec(String id, String label, List<VariableSpec> variables, MemorySettings memory,
        Set<String> defaultTags, Map<String, Double> defaultFeatures) {

    public ObjectTypeSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("object type id must not be blank");
        }
        variables = List.copyOf(variables);
        defaultTags = Set.copyOf(defaultTags);
        defaultFeatures = Map.copyOf(defaultFeatures);
        label = label == null || label.isBlank() ? id : label;
        long distinct = variables.stream().map(VariableSpec::name).distinct().count();
        if (distinct != variables.size()) {
            throw new IllegalArgumentException("duplicate variable names in object type " + id);
        }
    }

    public static ObjectTypeSpec of(String id, List<VariableSpec> variables, MemorySettings memory) {
        return new ObjectTypeSpec(id, id, variables, memory, Set.of(), Map.of());
    }

    public VariableSpec variable(String name) {
        for (VariableSpec variable : variables) {
            if (variable.name().equals(name)) {
                return variable;
            }
        }
        throw new IllegalArgumentException("object type " + id + " has no variable " + name);
    }

    public boolean hasVariable(String name) {
        for (VariableSpec variable : variables) {
            if (variable.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Starting values for a new instance of this type. */
    public Map<String, Double> initialValues() {
        Map<String, Double> values = new LinkedHashMap<>();
        for (VariableSpec variable : variables) {
            values.put(variable.name(), variable.initial());
        }
        return values;
    }
}
