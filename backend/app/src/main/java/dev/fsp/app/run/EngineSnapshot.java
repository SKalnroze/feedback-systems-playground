package dev.fsp.app.run;

import dev.fsp.engine.event.GeneratorState;
import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.memory.MemoryRecord;
import dev.fsp.engine.memory.Reactivation;
import dev.fsp.engine.state.DelayLine;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A checkpoint: everything needed to carry on a run exactly where it stopped.
 *
 * <p>Written as an explicit record rather than by serialising {@link SimulationState} directly.
 * The engine's state class is mutable and tuned for speed, and its field layout will change; the
 * stored format should not have to change with it. Going through a declared shape also means a
 * checkpoint written today stays readable after the engine is refactored.
 *
 * <p>The random seed and tick are enough to reproduce all future randomness, because draws are
 * derived from coordinates rather than from a carried generator state.
 */
public record EngineSnapshot(int formatVersion, long seed, long tick, Map<String, Double> globals,
        List<ObjectSnapshot> objects, List<MemorySnapshot> memories, long nextMemoryId, long forgottenCount,
        Map<String, GeneratorState> generators, Map<String, DelaySnapshot> delayLines) {

    /** Bumped whenever the shape changes in a way older readers could not handle. */
    public static final int CURRENT_FORMAT = 1;

    public record ObjectSnapshot(String id, String typeId, Map<String, Double> variables, Set<String> tags,
            Map<String, Double> features, boolean active) {
    }

    public record MemorySnapshot(long id, String ownerId, String subjectId, String kind, long createdTick,
            long originTick, double initialStrength, double valence, double salience, Map<String, Double> features,
            DecayModel decayModel, String sourceEventId, List<Reactivation> retainedReactivations,
            int totalReactivations, double totalBoost, double totalStabilityGain, int similarCount) {
    }

    /** @param pending in-flight contributions, oldest first */
    public record DelaySnapshot(int delayTicks, double[] pending) {
    }

    /** Captures the current state. The caller is responsible for not mutating it meanwhile. */
    public static EngineSnapshot capture(SimulationState state) {
        List<ObjectSnapshot> objects = new ArrayList<>();
        for (ObjectState object : state.allObjects()) {
            objects.add(new ObjectSnapshot(object.id(), object.typeId(), new LinkedHashMap<>(object.variables()),
                    Set.copyOf(object.tags()), new LinkedHashMap<>(object.features()), object.isActive()));
        }

        List<MemorySnapshot> memories = new ArrayList<>();
        state.memories().forEach(memory -> memories.add(new MemorySnapshot(memory.id(), memory.ownerId(),
                memory.subjectId(), memory.kind(), memory.createdTick(), memory.originTick(),
                memory.initialStrength(), memory.valence(), memory.salience(), memory.features(),
                memory.decayModel(), memory.sourceEventId(), List.copyOf(memory.reactivations()),
                memory.reactivationCount(), memory.totalBoost(), memory.totalStabilityGain(),
                memory.similarCount())));

        Map<String, DelaySnapshot> delays = new LinkedHashMap<>();
        for (Map.Entry<String, DelayLine> entry : state.delayLines().entrySet()) {
            delays.put(entry.getKey(),
                    new DelaySnapshot(entry.getValue().delayTicks(), entry.getValue().pending()));
        }

        return new EngineSnapshot(CURRENT_FORMAT, state.runSeed(), state.tick(), new LinkedHashMap<>(state.globals()),
                objects, memories, state.memories().peekNextId(), state.memories().forgottenCount(),
                new LinkedHashMap<>(state.generatorStates()), delays);
    }

    /** Rebuilds simulation state from this snapshot. */
    public SimulationState restore() {
        SimulationState state = new SimulationState(seed);
        state.restoreTick(tick);
        globals.forEach(state::setGlobal);

        for (ObjectSnapshot snapshot : objects) {
            ObjectState object = new ObjectState(snapshot.id(), snapshot.typeId());
            snapshot.variables().forEach(object::set);
            object.tags().addAll(snapshot.tags());
            object.features().putAll(snapshot.features());
            if (!snapshot.active()) {
                object.deactivate();
            }
            state.addObject(object);
        }

        for (MemorySnapshot snapshot : memories) {
            MemoryRecord.Builder builder = MemoryRecord
                    .builder(snapshot.id(), snapshot.ownerId(), snapshot.subjectId(), snapshot.createdTick(),
                            snapshot.decayModel())
                    .kind(snapshot.kind()).initialStrength(snapshot.initialStrength()).valence(snapshot.valence())
                    .salience(snapshot.salience()).features(snapshot.features())
                    .sourceEventId(snapshot.sourceEventId());
            state.memories()
                    .add(MemoryRecord.restore(builder, snapshot.originTick(), snapshot.retainedReactivations(),
                            snapshot.totalReactivations(), snapshot.totalBoost(), snapshot.totalStabilityGain(),
                            snapshot.similarCount()));
        }
        state.memories().restoreNextId(nextMemoryId);
        state.memories().restoreForgottenCount(forgottenCount);

        generators.forEach(state::setGeneratorState);
        delayLines.forEach((linkId, delay) -> state.delayLine(linkId, delay.delayTicks()).restore(delay.pending()));

        return state;
    }
}
