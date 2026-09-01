package dev.fsp.engine.state;

import dev.fsp.engine.event.GeneratorState;
import dev.fsp.engine.memory.MemoryStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that changes as a simulation runs.
 *
 * <p>Held mutable and stepped in place: a long run touches this structure millions of times, and
 * persistent immutable collections would cost more than they are worth here. Reproducibility comes
 * from the seeded RNG and ordered iteration instead, and {@link #copy()} gives checkpoints a clean
 * snapshot.
 */
public final class SimulationState {

    private final long runSeed;
    private final Map<String, ObjectState> objects = new LinkedHashMap<>();
    private final Map<String, Double> globals = new LinkedHashMap<>();
    private final Map<String, GeneratorState> generators = new LinkedHashMap<>();
    private final Map<String, DelayLine> delayLines = new LinkedHashMap<>();
    private final MemoryStore memories;
    private long tick;

    public SimulationState(long runSeed) {
        this(runSeed, new MemoryStore());
    }

    private SimulationState(long runSeed, MemoryStore memories) {
        this.runSeed = runSeed;
        this.memories = memories;
    }

    public long runSeed() {
        return runSeed;
    }

    public long tick() {
        return tick;
    }

    public void advanceTick() {
        tick++;
    }

    /** Sets the tick directly; only for restoring a checkpoint. */
    public void restoreTick(long tick) {
        this.tick = tick;
    }

    public MemoryStore memories() {
        return memories;
    }

    // --- objects -----------------------------------------------------------------------------

    public ObjectState addObject(ObjectState object) {
        objects.put(object.id(), object);
        return object;
    }

    public ObjectState object(String id) {
        ObjectState object = objects.get(id);
        if (object == null) {
            throw new IllegalArgumentException("no such object: " + id);
        }
        return object;
    }

    public boolean hasObject(String id) {
        return objects.containsKey(id);
    }

    /** Every object, including inactive ones, in insertion order. */
    public Collection<ObjectState> allObjects() {
        return objects.values();
    }

    /** Objects still taking part, in insertion order. */
    public List<ObjectState> activeObjects() {
        List<ObjectState> active = new ArrayList<>(objects.size());
        for (ObjectState object : objects.values()) {
            if (object.isActive()) {
                active.add(object);
            }
        }
        return active;
    }

    public List<ObjectState> objectsOfType(String typeId) {
        List<ObjectState> matches = new ArrayList<>();
        for (ObjectState object : objects.values()) {
            if (object.isActive() && object.typeId().equals(typeId)) {
                matches.add(object);
            }
        }
        return matches;
    }

    /**
     * Every active member of an authored object, in the order they were created.
     *
     * <p>Order matters: member-to-member couplings pair by position, and a pairing that shuffled
     * between ticks would make a link's effect depend on iteration order rather than on the model.
     */
    public List<ObjectState> membersOf(String groupId) {
        List<ObjectState> members = new ArrayList<>();
        for (ObjectState object : objects.values()) {
            if (object.isActive() && object.groupId().equals(groupId)) {
                members.add(object);
            }
        }
        members.sort(java.util.Comparator.comparingInt(ObjectState::memberIndex));
        return members;
    }

    /** Removes an object and everything remembered about it. */
    public void removeObject(String id) {
        objects.remove(id);
        memories.removeAllInvolving(id);
    }

    // --- global variables --------------------------------------------------------------------

    public double global(String name) {
        Double value = globals.get(name);
        if (value == null) {
            throw new IllegalArgumentException("no such global variable: " + name);
        }
        return value;
    }

    public boolean hasGlobal(String name) {
        return globals.containsKey(name);
    }

    public void setGlobal(String name, double value) {
        globals.put(name, value);
    }

    public Map<String, Double> globals() {
        return globals;
    }

    // --- event generator state ---------------------------------------------------------------

    public GeneratorState generatorState(String eventId) {
        return generators.getOrDefault(eventId, GeneratorState.INITIAL);
    }

    public void setGeneratorState(String eventId, GeneratorState state) {
        generators.put(eventId, state);
    }

    public Map<String, GeneratorState> generatorStates() {
        return generators;
    }

    // --- link delay lines --------------------------------------------------------------------

    public DelayLine delayLine(String linkId, int delayTicks) {
        return delayLines.computeIfAbsent(linkId, key -> new DelayLine(delayTicks));
    }

    public Map<String, DelayLine> delayLines() {
        return delayLines;
    }

    /** Deep copy for checkpointing or forking a run. */
    public SimulationState copy() {
        SimulationState copy = new SimulationState(runSeed, memories.copy());
        copy.tick = tick;
        for (Map.Entry<String, ObjectState> entry : objects.entrySet()) {
            copy.objects.put(entry.getKey(), entry.getValue().copy());
        }
        copy.globals.putAll(globals);
        copy.generators.putAll(generators);
        for (Map.Entry<String, DelayLine> entry : delayLines.entrySet()) {
            copy.delayLines.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }
}
