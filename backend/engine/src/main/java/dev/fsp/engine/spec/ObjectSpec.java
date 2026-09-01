package dev.fsp.engine.spec;

import java.util.Map;
import java.util.Set;

/**
 * One object as authored, or a group of identical ones.
 *
 * <p>A population is expressed by count rather than by repetition: a workforce of two thousand is
 * one node carrying the number, not two thousand nodes nobody can lay out or read. Every member is
 * still a real object at run time, with its own variables and its own memories - the count is an
 * authoring convenience, not a statistical shortcut.
 *
 * @param variables overrides on top of the type's initial values; missing entries take the default
 * @param count     how many instances to create; 1 is an ordinary single object
 */
public record ObjectSpec(String id, String typeId, String label, Map<String, Double> variables, Set<String> tags,
        Map<String, Double> features, int count) {

    /** Past this many members a group is worth a warning, not a refusal. */
    public static final int SOFT_MEMBER_LIMIT = 2_000;

    /** Past this many the process itself is at risk, so the spec is rejected. */
    public static final int MAX_MEMBERS = 10_000;

    /** Keeps every caller written before groups existed compiling, and means the same thing. */
    public ObjectSpec(String id, String typeId, String label, Map<String, Double> variables, Set<String> tags,
            Map<String, Double> features) {
        this(id, typeId, label, variables, tags, features, 1);
    }

    public ObjectSpec {
        // Zero arrives from JSON written before the field existed; it means one object, not none.
        if (count < 1) {
            count = 1;
        }
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
        return new ObjectSpec(id, typeId, id, Map.of(), Set.of(), Map.of(), 1);
    }

    /** True when this authors more than one instance. */
    public boolean isGroup() {
        return count > 1;
    }

    public ObjectSpec times(int members) {
        return new ObjectSpec(id, typeId, label, variables, tags, features, members);
    }

    /**
     * The id of one member.
     *
     * <p>A single object keeps its plain id, so nothing authored before groups existed changes
     * name - which matters because ids are series keys, and a renamed series is a broken chart.
     */
    public String memberId(int index) {
        return count == 1 ? id : id + "#" + (index + 1);
    }

    public ObjectSpec with(String variable, double value) {
        Map<String, Double> merged = new java.util.LinkedHashMap<>(variables);
        merged.put(variable, value);
        return new ObjectSpec(id, typeId, label, merged, tags, features, count);
    }

    public ObjectSpec tagged(String... extra) {
        Set<String> merged = new java.util.LinkedHashSet<>(tags);
        merged.addAll(Set.of(extra));
        return new ObjectSpec(id, typeId, label, variables, merged, features, count);
    }

    public ObjectSpec featured(String feature, double weight) {
        Map<String, Double> merged = new java.util.LinkedHashMap<>(features);
        merged.put(feature, weight);
        return new ObjectSpec(id, typeId, label, variables, tags, merged, count);
    }
}
