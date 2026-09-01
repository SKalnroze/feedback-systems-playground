package dev.fsp.engine.state;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One object in a running simulation: its variables, tags and feature vector.
 *
 * <p>Mutable for the same reason {@code MemoryRecord} is - every tick writes to it - with an
 * explicit {@link #copy()} for checkpoints. Maps are insertion-ordered so iteration cannot vary
 * between runs.
 */
public final class ObjectState {

    private final String id;
    private final String typeId;
    /** The authored object this was materialised from; equal to the id for a single object. */
    private final String groupId;
    /** Position within that group, zero for a single object. Fixes pairing order for couplings. */
    private final int memberIndex;
    private final Map<String, Double> variables = new LinkedHashMap<>();
    private final Map<String, Double> features = new LinkedHashMap<>();
    private final Set<String> tags = new LinkedHashSet<>();
    private boolean active = true;

    public ObjectState(String id, String typeId) {
        this(id, typeId, id, 0);
    }

    public ObjectState(String id, String typeId, String groupId, int memberIndex) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.typeId = java.util.Objects.requireNonNull(typeId, "typeId");
        this.groupId = groupId == null ? id : groupId;
        this.memberIndex = Math.max(0, memberIndex);
    }

    /** The authored object this came from; for a single object, its own id. */
    public String groupId() {
        return groupId;
    }

    public int memberIndex() {
        return memberIndex;
    }

    public String id() {
        return id;
    }

    public String typeId() {
        return typeId;
    }

    /** Whether the object is still taking part; inactive objects are skipped by every rule. */
    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        active = false;
    }

    public Map<String, Double> variables() {
        return variables;
    }

    public Set<String> tags() {
        return tags;
    }

    /** Stable traits used as cue features, e.g. which team someone is on. */
    public Map<String, Double> features() {
        return features;
    }

    public boolean has(String variable) {
        return variables.containsKey(variable);
    }

    /**
     * @throws IllegalArgumentException if the variable is not declared on this object
     */
    public double get(String variable) {
        Double value = variables.get(variable);
        if (value == null) {
            throw new IllegalArgumentException("object " + id + " has no variable " + variable);
        }
        return value;
    }

    public double getOrDefault(String variable, double fallback) {
        return variables.getOrDefault(variable, fallback);
    }

    public void set(String variable, double value) {
        variables.put(variable, value);
    }

    public ObjectState withVariable(String variable, double value) {
        variables.put(variable, value);
        return this;
    }

    public ObjectState withTag(String tag) {
        tags.add(tag);
        return this;
    }

    public ObjectState withFeature(String feature, double weight) {
        features.put(feature, weight);
        return this;
    }

    public ObjectState copy() {
        ObjectState copy = new ObjectState(id, typeId);
        copy.variables.putAll(variables);
        copy.features.putAll(features);
        copy.tags.addAll(tags);
        copy.active = active;
        return copy;
    }

    @Override
    public String toString() {
        return "Object[" + id + ":" + typeId + " " + variables + "]";
    }
}
