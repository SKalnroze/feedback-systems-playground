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
        Set<String> defaultTags, Map<String, Double> defaultFeatures, String templateId, Integer templateVersion) {

    /** Keeps every caller written before templates existed compiling; such a type has no origin. */
    public ObjectTypeSpec(String id, String label, List<VariableSpec> variables, MemorySettings memory,
            Set<String> defaultTags, Map<String, Double> defaultFeatures) {
        this(id, label, variables, memory, defaultTags, defaultFeatures, null, null);
    }

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
        return new ObjectTypeSpec(id, id, variables, memory, Set.of(), Map.of(), null, null);
    }

    /** True when this type was copied out of a shared template and can go out of date. */
    public boolean fromTemplate() {
        return templateId != null && templateVersion != null;
    }

    /** Records where a type came from, so the editor can notice the template moving on without it. */
    public ObjectTypeSpec tracking(String template, int version) {
        return new ObjectTypeSpec(id, label, variables, memory, defaultTags, defaultFeatures, template, version);
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
