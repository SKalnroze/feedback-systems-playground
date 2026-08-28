package dev.fsp.engine.event;

import dev.fsp.engine.expr.EvalContext;
import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.memory.MemoryRecord;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;
import dev.fsp.engine.state.BoundEvalContext;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@link EffectContext} that records what effects asked for, without a whole engine behind it. */
final class RecordingEffectContext implements EffectContext {

    private final SimulationState state;
    private final TargetSelector.Target target;
    final List<String> emittedEvents = new ArrayList<>();
    final List<String> logLines = new ArrayList<>();
    final List<MemoryRecord> memories = new ArrayList<>();

    RecordingEffectContext(SimulationState state, TargetSelector.Target target) {
        this.state = state;
        this.target = target;
    }

    @Override
    public SimulationState state() {
        return state;
    }

    @Override
    public long tick() {
        return state.tick();
    }

    @Override
    public EvalContext evalContext() {
        return BoundEvalContext.of(state, target.primary(), target.secondary());
    }

    @Override
    public Rng rng() {
        return Rng.of(state.runSeed(), state.tick(), RngStream.EVENT_EFFECT);
    }

    @Override
    public String sourceEventId() {
        return "test-event";
    }

    @Override
    public void injectMemory(ObjectState owner, ObjectState subject, String kind, double strength, double valence,
            double salience, Map<String, Double> features, DecayModel decayOverride) {
        MemoryRecord record = MemoryRecord
                .builder(state.memories().nextId(), owner.id(), subject.id(), state.tick(),
                        decayOverride == null ? DecayModel.NoDecay.INSTANCE : decayOverride)
                .kind(kind).initialStrength(strength).valence(valence).salience(salience).features(features).build();
        state.memories().add(record);
        memories.add(record);
    }

    @Override
    public void emitEvent(String eventId) {
        emittedEvents.add(eventId);
    }

    @Override
    public ObjectState spawnObject(String typeId) {
        return state.addObject(new ObjectState(typeId + "-" + (state.allObjects().size() + 1), typeId));
    }

    @Override
    public void log(String message) {
        logLines.add(message);
    }
}
