package dev.fsp.engine;

import dev.fsp.engine.TickReport.EventOccurrence;
import dev.fsp.engine.TickReport.ReactivationRecord;
import dev.fsp.engine.event.Effect;
import dev.fsp.engine.event.EffectContext;
import dev.fsp.engine.event.EventGenerator;
import dev.fsp.engine.event.EventRandom;
import dev.fsp.engine.event.GeneratorState;
import dev.fsp.engine.event.TargetSelector;
import dev.fsp.engine.expr.EvalContext;
import dev.fsp.engine.graph.VariableGraph;
import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.memory.MemoryRecord;
import dev.fsp.engine.memory.MemorySettings;
import dev.fsp.engine.memory.MemoryTrigger;
import dev.fsp.engine.memory.TriggerContext;
import dev.fsp.engine.module.InteractionContext;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;
import dev.fsp.engine.spec.EventSpec;
import dev.fsp.engine.spec.ObjectSpec;
import dev.fsp.engine.spec.ObjectTypeSpec;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.spec.VariableSpec;
import dev.fsp.engine.state.BoundEvalContext;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a system one tick at a time.
 *
 * <p>The engine is deliberately passive: it holds no threads, no clock and no storage. A caller
 * decides when to step it, and everything a step did comes back in a {@link TickReport}. That is
 * what makes the same code usable from a background run, a unit test and a replay.
 *
 * <p>Each tick runs in a fixed order:
 * <ol>
 *   <li>external events are generated and their effects applied;
 *   <li>objects interact with each other under the module rules;
 *   <li>memories are reactivated by whatever the first two phases put in play;
 *   <li>memories decay and, where configured, are forgotten;
 *   <li>the variable graph propagates contributions along its links.
 * </ol>
 *
 * <p>Reactivation sits after interactions rather than between events and interactions, because a
 * co-presence or cue trigger has to be able to see what happened this tick: bumping into someone
 * should bring back what you remember about them now, not one tick later.
 */
public final class Engine {

    /** Guards against an event cascade that emits itself forever. */
    private static final int MAX_CASCADE_DEPTH = 32;

    private final SystemSpec spec;
    private final Map<String, InteractionRule> rules = new LinkedHashMap<>();
    private final VariableGraph graph;

    public Engine(SystemSpec spec, List<InteractionRule> availableRules) {
        this.spec = spec;
        this.graph = new VariableGraph(spec);
        for (InteractionRule rule : availableRules) {
            rules.put(rule.id(), rule);
        }
    }

    public SystemSpec spec() {
        return spec;
    }

    public VariableGraph graph() {
        return graph;
    }

    /** Builds the tick-zero state: objects at their initial values, no memories, nothing in flight. */
    public SimulationState createInitialState(long seed) {
        SimulationState state = new SimulationState(seed);
        for (VariableSpec variable : spec.globalVariables()) {
            state.setGlobal(variable.name(), variable.initial());
        }
        for (ObjectSpec objectSpec : spec.objects()) {
            ObjectTypeSpec type = spec.objectType(objectSpec.typeId());
            // One authored object becomes `count` instances. They start identical; what makes them
            // diverge is what happens to them, which is the whole point of simulating a population
            // rather than scaling one representative by a number.
            for (int member = 0; member < objectSpec.count(); member++) {
                ObjectState object = new ObjectState(objectSpec.memberId(member), objectSpec.typeId(),
                        objectSpec.id(), member);
                type.initialValues().forEach(object::set);
                objectSpec.variables().forEach((name, value) -> {
                    if (type.hasVariable(name)) {
                        object.set(name, type.variable(name).clamp(value));
                    }
                });
                object.tags().addAll(type.defaultTags());
                object.tags().addAll(objectSpec.tags());
                object.features().putAll(type.defaultFeatures());
                object.features().putAll(objectSpec.features());
                state.addObject(object);
            }
        }
        for (EventSpec event : spec.events()) {
            if (event.generator() instanceof EventGenerator.MarkovChain chain) {
                state.setGeneratorState(event.id(), GeneratorState.startingIn(chain.initialStateId()));
            }
        }
        graph.primeRateBaseline(state);
        return state;
    }

    /** Advances the simulation by one tick and reports what happened. */
    public TickReport tick(SimulationState state) {
        state.advanceTick();
        TickScope scope = new TickScope(state);

        runEvents(state, scope);
        runInteractions(state, scope);
        runReactivations(state, scope);
        int forgotten = decayAndPrune(state);
        Map<String, Double> contributions = graph.propagate(state);

        return new TickReport(state.tick(), scope.occurrences, scope.reactivations, scope.logLines,
                scope.memoriesCreated, forgotten, contributions);
    }

    // --- phase 1: external events ------------------------------------------------------------

    private void runEvents(SimulationState state, TickScope scope) {
        Deque<String> cascade = new ArrayDeque<>();

        for (EventSpec event : spec.events()) {
            GeneratorState generatorState = state.generatorState(event.id());
            if (isSuppressed(event, generatorState, state.tick())) {
                continue;
            }
            EventGenerator.Outcome outcome = event.generator().generate(state.tick(),
                    EventRandom.forEvent(state.runSeed(), event.id()), generatorState);
            state.setGeneratorState(event.id(), outcome.nextState());

            for (int occurrence = 0; occurrence < outcome.occurrences(); occurrence++) {
                fire(state, scope, event, occurrence, false, cascade);
            }
        }

        int depth = 0;
        while (!cascade.isEmpty() && depth++ < MAX_CASCADE_DEPTH) {
            String eventId = cascade.removeFirst();
            EventSpec event = spec.event(eventId);
            GeneratorState generatorState = state.generatorState(eventId);
            if (isSuppressed(event, generatorState, state.tick())) {
                continue;
            }
            state.setGeneratorState(eventId, generatorState.recording(state.tick(), 1));
            fire(state, scope, event, depth, true, cascade);
        }
        if (depth >= MAX_CASCADE_DEPTH) {
            scope.logLines.add("event cascade truncated at depth " + MAX_CASCADE_DEPTH);
        }
    }

    private boolean isSuppressed(EventSpec event, GeneratorState generatorState, long tick) {
        if (event.hasOccurrenceLimit() && generatorState.totalOccurrences() >= event.maxOccurrences()) {
            return true;
        }
        return event.cooldownTicks() > 0 && generatorState.ticksSinceLastOccurrence(tick) < event.cooldownTicks();
    }

    private void fire(SimulationState state, TickScope scope, EventSpec event, int occurrence, boolean cascaded,
            Deque<String> cascade) {
        Rng targetingRng = Rng.of(state.runSeed(), state.tick(), RngStream.EVENT_TARGETING, event.id().hashCode())
                .derive(occurrence);
        List<TargetSelector.Target> targets = event.selector().select(state, targetingRng);

        List<String> targetIds = new ArrayList<>(targets.size());
        boolean anyApplied = false;
        for (TargetSelector.Target target : targets) {
            BoundEvalContext evalContext = BoundEvalContext.of(state, target.primary(), target.secondary());
            if (!event.condition().test(evalContext)) {
                continue;
            }
            anyApplied = true;
            if (target.primary() != null) {
                targetIds.add(target.primary().id());
                scope.inPlay.add(target.primary().id());
            }
            if (target.secondary() != null) {
                scope.inPlay.add(target.secondary().id());
            }
            EventEffectContext effectContext = new EventEffectContext(scope, evalContext, event.id(),
                    Rng.of(state.runSeed(), state.tick(), RngStream.EVENT_EFFECT, event.id().hashCode())
                            .derive(targetIds.size()),
                    cascade);
            for (Effect effect : event.effects()) {
                effect.apply(effectContext, target);
            }
        }

        if (anyApplied) {
            scope.firedEventIds.add(event.id());
            scope.occurrences.add(new EventOccurrence(event.id(), targetIds, cascaded));
        }
    }

    // --- phase 2: interactions ---------------------------------------------------------------

    private void runInteractions(SimulationState state, TickScope scope) {
        for (InteractionRule.Config config : spec.interactions()) {
            if (!config.enabled()) {
                continue;
            }
            InteractionRule rule = rules.get(config.ruleId());
            if (rule == null) {
                scope.logLines.add("interaction rule not available: " + config.ruleId());
                continue;
            }
            rule.execute(new RuleContext(scope, config));
        }
    }

    // --- phase 3: reactivation ---------------------------------------------------------------

    private void runReactivations(SimulationState state, TickScope scope) {
        if (spec.triggers().isEmpty()) {
            return;
        }
        long tick = state.tick();
        for (MemoryTrigger trigger : spec.triggers()) {
            for (String ownerId : state.memories().owners()) {
                int fired = 0;
                // Indexed rather than a for-each over a copy: a reactivation can append a derived
                // memory to this very list, and the snapshot bound keeps it out of this pass.
                List<MemoryRecord> memories = state.memories().of(ownerId);
                for (int index = 0, bound = memories.size(); index < bound; index++) {
                    MemoryRecord memory = memories.get(index);
                    if (trigger.hasPerTickLimit() && fired >= trigger.maxPerTick()) {
                        break;
                    }
                    if (!trigger.filter().accepts(memory, tick)) {
                        continue;
                    }
                    if (!trigger.condition().matches(scope, memory)) {
                        continue;
                    }
                    fired++;
                    applyReactivation(scope, trigger, memory, tick);
                }
            }
        }
        fireEventsEmittedByRecall(state, scope);
    }

    /**
     * Events that reactivation asked for. Recalling a grievance can start an argument, and that
     * argument has to happen in the same tick or the causal chain becomes impossible to read.
     */
    private void fireEventsEmittedByRecall(SimulationState state, TickScope scope) {
        Deque<String> cascade = new ArrayDeque<>(scope.pendingEmissions);
        scope.pendingEmissions.clear();
        int depth = 0;
        while (!cascade.isEmpty() && depth++ < MAX_CASCADE_DEPTH) {
            String eventId = cascade.removeFirst();
            EventSpec event = spec.event(eventId);
            GeneratorState generatorState = state.generatorState(eventId);
            if (isSuppressed(event, generatorState, state.tick())) {
                continue;
            }
            state.setGeneratorState(eventId, generatorState.recording(state.tick(), 1));
            fire(state, scope, event, depth, true, cascade);
        }
    }

    private void applyReactivation(TickScope scope, MemoryTrigger trigger, MemoryRecord memory, long tick) {
        MemoryTrigger.ReactivationEffect effect = trigger.effect();
        double before = memory.strengthAt(tick);
        memory.reactivate(effect.toReactivation(tick, trigger.id()));
        if (effect.valenceShift() != 0.0) {
            memory.shiftValence(effect.valenceShift());
        }
        double after = memory.strengthAt(tick);
        scope.reactivations
                .add(new ReactivationRecord(memory.id(), memory.ownerId(), memory.subjectId(), trigger.id(), before,
                        after));

        if (effect.spawnsDerivedMemory() && scope.state.hasObject(memory.ownerId())
                && scope.state.hasObject(memory.subjectId())) {
            // Recalling something is itself an experience, and can be remembered in its own right.
            scope.injectMemory(scope.state.object(memory.ownerId()), scope.state.object(memory.subjectId()),
                    effect.spawnDerived(), after * effect.derivedStrength(), memory.valence(), memory.salience(),
                    memory.features(), null, trigger.id());
        }
        if (effect.emitsEvent()) {
            scope.pendingEmissions.add(effect.emitEventId());
        }
    }

    // --- phase 4: decay and forgetting -------------------------------------------------------

    private int decayAndPrune(SimulationState state) {
        int forgotten = 0;
        for (String ownerId : state.memories().owners()) {
            if (!state.hasObject(ownerId)) {
                continue;
            }
            MemorySettings settings = memorySettingsFor(state.object(ownerId));
            forgotten += state.memories().prune(ownerId, state.tick(), settings);
        }
        return forgotten;
    }

    private MemorySettings memorySettingsFor(ObjectState object) {
        return spec.hasObjectType(object.typeId()) ? spec.objectType(object.typeId()).memory()
                : MemorySettings.DEFAULT;
    }

    /**
     * Per-tick mutable scratch space, and the implementation of the three context interfaces the
     * subsystems talk to. Keeping them on one object means a memory injected by an event effect is
     * immediately visible to a cue trigger later in the same tick.
     */
    private final class TickScope implements TriggerContext {

        private final SimulationState state;
        private final List<EventOccurrence> occurrences = new ArrayList<>();
        private final List<ReactivationRecord> reactivations = new ArrayList<>();
        private final List<String> logLines = new ArrayList<>();
        private final Set<String> firedEventIds = new LinkedHashSet<>();
        private final Set<String> inPlay = new LinkedHashSet<>();
        private final Map<String, Double> cue = new LinkedHashMap<>();
        private final List<String> pendingEmissions = new ArrayList<>();
        private int memoriesCreated;

        private TickScope(SimulationState state) {
            this.state = state;
        }

        void injectMemory(ObjectState owner, ObjectState subject, String kind, double strength, double valence,
                double salience, Map<String, Double> features, DecayModel decayOverride, String sourceId) {
            MemorySettings settings = memorySettingsFor(owner);
            DecayModel model = decayOverride != null ? decayOverride : settings.defaultDecay();
            MemoryRecord record = MemoryRecord
                    .builder(state.memories().nextId(), owner.id(), subject.id(), state.tick(), model).kind(kind)
                    .initialStrength(strength).valence(valence).salience(salience).features(features)
                    .sourceEventId(sourceId).build();
            if (settings.usesInterference()) {
                state.memories().addTracked(record, settings.similarityThreshold());
            } else {
                state.memories().add(record);
            }
            memoriesCreated++;
            inPlay.add(owner.id());
            inPlay.add(subject.id());
            // What just happened becomes part of this tick's cue, so similar memories can surface.
            features.forEach((feature, weight) -> cue.merge(feature, weight, Double::max));
        }

        @Override
        public long tick() {
            return state.tick();
        }

        @Override
        public Set<String> firedEventIds() {
            return firedEventIds;
        }

        @Override
        public Set<String> objectsInPlay() {
            return inPlay;
        }

        @Override
        public Map<String, Double> cue() {
            return cue;
        }

        @Override
        public EvalContext evalContextFor(MemoryRecord memory) {
            ObjectState owner = state.hasObject(memory.ownerId()) ? state.object(memory.ownerId()) : null;
            ObjectState subject = state.hasObject(memory.subjectId()) ? state.object(memory.subjectId()) : null;
            return BoundEvalContext.of(state, owner, subject);
        }

        @Override
        public Rng rngFor(MemoryRecord memory) {
            return Rng.of(state.runSeed(), state.tick(), RngStream.TRIGGER_STOCHASTIC, memory.id());
        }
    }

    /**
     * Clamps a value to a variable's declared bounds.
     *
     * <p>Looked up from the spec rather than carried on the state: bounds are a property of the
     * model, and a checkpoint should not have to store them to be restorable.
     */
    double clampToDeclaredRange(String typeId, String variable, double value) {
        VariableSpec declared = null;
        if (typeId == null) {
            for (VariableSpec candidate : spec.globalVariables()) {
                if (candidate.name().equals(variable)) {
                    declared = candidate;
                    break;
                }
            }
        } else {
            for (ObjectTypeSpec type : spec.objectTypes()) {
                if (!type.id().equals(typeId)) {
                    continue;
                }
                for (VariableSpec candidate : type.variables()) {
                    if (candidate.name().equals(variable)) {
                        declared = candidate;
                        break;
                    }
                }
            }
        }
        // An undeclared variable has no range to honour; rules are free to invent scratch values.
        return declared == null ? value : Math.clamp(value, declared.min(), declared.max());
    }

    /** {@link EffectContext} for one event application. */
    private final class EventEffectContext implements EffectContext {

        private final TickScope scope;
        private final EvalContext evalContext;
        private final String eventId;
        private final Rng rng;
        private final Deque<String> cascade;

        private EventEffectContext(TickScope scope, EvalContext evalContext, String eventId, Rng rng,
                Deque<String> cascade) {
            this.scope = scope;
            this.evalContext = evalContext;
            this.eventId = eventId;
            this.rng = rng;
            this.cascade = cascade;
        }

        @Override
        public double clampToDeclaredRange(String typeId, String variable, double value) {
            return Engine.this.clampToDeclaredRange(typeId, variable, value);
        }

        @Override
        public SimulationState state() {
            return scope.state;
        }

        @Override
        public long tick() {
            return scope.state.tick();
        }

        @Override
        public EvalContext evalContext() {
            return evalContext;
        }

        @Override
        public Rng rng() {
            return rng;
        }

        @Override
        public String sourceEventId() {
            return eventId;
        }

        @Override
        public void injectMemory(ObjectState owner, ObjectState subject, String kind, double strength, double valence,
                double salience, Map<String, Double> features, DecayModel decayOverride) {
            scope.injectMemory(owner, subject, kind, strength, valence, salience, features, decayOverride, eventId);
        }

        @Override
        public void emitEvent(String emittedId) {
            cascade.addLast(emittedId);
        }

        @Override
        public ObjectState spawnObject(String typeId) {
            ObjectTypeSpec type = spec.objectType(typeId);
            String id = typeId + "-" + (scope.state.allObjects().size() + 1);
            ObjectState object = new ObjectState(id, typeId);
            type.initialValues().forEach(object::set);
            object.tags().addAll(type.defaultTags());
            object.features().putAll(type.defaultFeatures());
            return scope.state.addObject(object);
        }

        @Override
        public void log(String message) {
            scope.logLines.add(message);
        }
    }

    /** {@link InteractionContext} for one module rule. */
    private final class RuleContext implements InteractionContext {

        private final TickScope scope;
        private final InteractionRule.Config config;

        private RuleContext(TickScope scope, InteractionRule.Config config) {
            this.scope = scope;
            this.config = config;
        }

        @Override
        public SimulationState state() {
            return scope.state;
        }

        @Override
        public long tick() {
            return scope.state.tick();
        }

        @Override
        public Rng rng(RngStream stream) {
            return Rng.of(scope.state.runSeed(), scope.state.tick(), stream, config.ruleId().hashCode());
        }

        @Override
        public double param(String name, double fallback) {
            return config.params().getOrDefault(name, fallback);
        }

        @Override
        public void injectMemory(ObjectState owner, ObjectState subject, String kind, double strength, double valence,
                double salience, Map<String, Double> features, DecayModel decayOverride) {
            scope.injectMemory(owner, subject, kind, strength, valence, salience, features, decayOverride,
                    config.ruleId());
        }

        @Override
        public void markInPlay(ObjectState object) {
            scope.inPlay.add(object.id());
        }

        @Override
        public void addCue(String feature, double weight) {
            scope.cue.merge(feature, weight, Double::max);
        }

        @Override
        public void log(String message) {
            scope.logLines.add(message);
        }
    }
}
