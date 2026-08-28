package dev.fsp.engine.spec;

import java.util.Map;
import java.util.Set;

/**
 * One object instance as authored.
 *
 * @param variables overrides on top of the type's initial values; missing entries take the default
 */
public record ObjectSpec(String id, String typeId, String label, Map<String, Double> variables, Set<String> tags,
        Map<String, Double> features) {

    public ObjectSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("object id must not be blank");
        }
        if (typeId == null || typeId.isBlank()) {
            throw new IllegalArgumentException("object " + id + " has no type");
        }
        variables = Map.copyOf(variables);
        tags = Set.copyOf(tags);
        features = Map.copyOf(features);
        label = label == null || label.isBlank() ? id : label;
    }

    public static ObjectSpec of(String id, String typeId) {
        return new ObjectSpec(id, typeId, id, Map.of(), Set.of(), Map.of());
    }

    public ObjectSpec with(String variable, double value) {
        Map<String, Double> merged = new java.util.LinkedHashMap<>(variables);
        merged.put(variable, value);
        return new ObjectSpec(id, typeId, label, merged, tags, features);
    }

    public ObjectSpec tagged(String... extra) {
        Set<String> merged = new java.util.LinkedHashSet<>(tags);
        merged.addAll(Set.of(extra));
        return new ObjectSpec(id, typeId, label, variables, merged, features);
    }

    public ObjectSpec featured(String feature, double weight) {
        Map<String, Double> merged = new java.util.LinkedHashMap<>(features);
        merged.put(feature, weight);
        return new ObjectSpec(id, typeId, label, variables, tags, merged);
    }
}
