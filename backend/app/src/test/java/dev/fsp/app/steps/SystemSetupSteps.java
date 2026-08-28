package dev.fsp.app.steps;

import dev.fsp.app.support.World;
import dev.fsp.engine.event.Effect;
import dev.fsp.engine.event.EventGenerator;
import dev.fsp.engine.event.TargetSelector;
import dev.fsp.engine.expr.Scope;
import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.memory.MemorySettings;
import dev.fsp.engine.memory.MemoryTrigger;
import dev.fsp.engine.memory.MemoryTrigger.MemoryFilter;
import dev.fsp.engine.memory.MemoryTrigger.ReactivationEffect;
import dev.fsp.engine.memory.TriggerCondition;
import dev.fsp.engine.spec.EventSpec;
import io.cucumber.java.en.Given;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;

/** Steps that build up the system under test before a run is created. */
public class SystemSetupSteps {

    @Autowired
    private World world;

    @Given("a system called {string} with two people")
    public void aSystemWithTwoPeople(String name) {
        world.namedSystem(name);
        world.addPerson("ana");
        world.addPerson("ben");
    }

    // --- memory configuration -------------------------------------------------------------------

    @Given("memories decay exponentially with a half-life of {int} ticks")
    public void exponentialDecay(int halfLife) {
        world.memorySettings(withModel(DecayModel.Exponential.ofHalfLife(halfLife)));
    }

    @Given("memories decay exponentially with a half-life of {int} ticks and a floor of {double}")
    public void exponentialDecayWithFloor(int halfLife, double floor) {
        world.memorySettings(
                withModel(new DecayModel.Exponential(halfLife, DecayModel.DecayCommon.floor(floor))));
    }

    @Given("memories decay by power law with exponent {double}")
    public void powerLawDecay(double exponent) {
        world.memorySettings(withModel(DecayModel.PowerLaw.ofExponent(exponent)));
    }

    @Given("forgotten memories are pruned below a strength of {double}")
    public void pruneBelow(double threshold) {
        MemorySettings current = world.memorySettings();
        world.memorySettings(new MemorySettings(current.defaultDecay(), threshold, current.capacity(), true,
                current.similarityThreshold()));
    }

    @Given("forgotten memories are kept")
    public void keepForgotten() {
        MemorySettings current = world.memorySettings();
        world.memorySettings(new MemorySettings(current.defaultDecay(), current.retrievalThreshold(),
                current.capacity(), false, current.similarityThreshold()));
    }

    // --- seeding memories -----------------------------------------------------------------------

    @Given("{string} remembers an interaction with {string} at full strength")
    public void remembersInteraction(String owner, String subject) {
        world.addEvent(world.memoryEvent("seed-interaction", 1L, owner, subject, "interaction", 1.0, 0.0));
    }

    @Given("{string} remembers an observation of {string} at full strength")
    public void remembersObservation(String owner, String subject) {
        world.addEvent(world.memoryEvent("seed-observation", 1L, owner, subject, "observation", 1.0, 0.0));
    }

    @Given("{string} remembers an interaction with {string} at full strength with valence {double}")
    public void remembersInteractionWithValence(String owner, String subject, double valence) {
        world.addEvent(world.memoryEvent("seed-interaction", 1L, owner, subject, "interaction", 1.0, valence));
    }

    // --- triggers ---------------------------------------------------------------------------------

    @Given("a trigger reactivates memories when the event {string} fires")
    public void triggerOnEvent(String eventId) {
        world.addTrigger(new MemoryTrigger("on-" + eventId, "on " + eventId, MemoryFilter.ANY,
                new TriggerCondition.OnEvent(Set.of(eventId)), rehearseFully(), 0));
    }

    @Given("a trigger reactivates memories every {int} ticks")
    public void triggerPeriodic(int interval) {
        world.addTrigger(new MemoryTrigger("periodic", "periodic", MemoryFilter.ANY,
                new TriggerCondition.Periodic(interval, 0L), rehearseFully(), 0));
    }

    @Given("a trigger reactivates memories every {int} ticks and shifts valence by {double}")
    public void triggerPeriodicWithValenceShift(int interval, double shift) {
        world.addTrigger(new MemoryTrigger("periodic", "periodic", MemoryFilter.ANY,
                new TriggerCondition.Periodic(interval, 0L),
                new ReactivationEffect(0.0, 1.0, true, shift, null, 0.0, null), 0));
    }

    @Given("a trigger reactivates only {string} memories every {int} ticks")
    public void triggerPeriodicForKind(String kind, int interval) {
        world.addTrigger(new MemoryTrigger("periodic-" + kind, "periodic " + kind, MemoryFilter.ofKinds(kind),
                new TriggerCondition.Periodic(interval, 0L), rehearseFully(), 0));
    }

    // --- events -----------------------------------------------------------------------------------

    @Given("the event {string} is scheduled for tick {long}")
    public void scheduledEvent(String id, long tick) {
        world.addEvent(EventSpec.of(id, EventGenerator.FixedSchedule.at(tick), TargetSelector.Everyone.all(),
                List.of(Effect.AdjustVariable.add(Scope.SELF, "resentment", 0.0))));
    }

    @Given("the event {string} is scheduled for tick {long} and lowers trust by {double}")
    public void scheduledEventLoweringTrust(String id, long tick, double amount) {
        world.addEvent(EventSpec.of(id, EventGenerator.FixedSchedule.at(tick), TargetSelector.Everyone.all(),
                List.of(Effect.AdjustVariable.add(Scope.SELF, "trust", -amount))));
    }

    @Given("the event {string} fires with probability {double} each tick and lowers trust by {double}")
    public void probabilisticEvent(String id, double probability, double amount) {
        world.addEvent(EventSpec.of(id, new EventGenerator.Bernoulli(probability), TargetSelector.Everyone.all(),
                List.of(Effect.AdjustVariable.add(Scope.SELF, "trust", -amount))));
    }

    @Given("the event {string} fires every tick and lowers trust by {double}")
    public void everyTickEvent(String id, double amount) {
        world.addEvent(EventSpec.of(id, EventGenerator.Periodic.every(1L), TargetSelector.Everyone.all(),
                List.of(Effect.AdjustVariable.add(Scope.SELF, "trust", -amount))));
    }

    @Given("the event {string} fires every tick with a cooldown of {int} ticks and lowers trust by {double}")
    public void cooldownEvent(String id, int cooldown, double amount) {
        world.addEvent(EventSpec
                .of(id, EventGenerator.Periodic.every(1L), TargetSelector.Everyone.all(),
                        List.of(Effect.AdjustVariable.add(Scope.SELF, "trust", -amount)))
                .withCooldown(cooldown));
    }

    @Given("the event {string} fires every tick at most {int} times and lowers trust by {double}")
    public void limitedEvent(String id, int limit, double amount) {
        world.addEvent(EventSpec
                .of(id, EventGenerator.Periodic.every(1L), TargetSelector.Everyone.all(),
                        List.of(Effect.AdjustVariable.add(Scope.SELF, "trust", -amount)))
                .limitedTo(limit));
    }

    @Given("the event {string} also leaves a memory")
    public void eventLeavesMemory(String id) {
        world.addEvent(EventSpec.of(id + "-memory", EventGenerator.Periodic.every(1L),
                new TargetSelector.NamedPair("ana", "ben"),
                List.of(Effect.InjectMemory.of("interaction", 0.9, -0.4))));
    }

    /** Full-strength rehearsal: the clock restarts, so the memory is as vivid as when it was made. */
    private static ReactivationEffect rehearseFully() {
        return new ReactivationEffect(0.0, 1.0, true, 0.0, null, 0.0, null);
    }

    private MemorySettings withModel(DecayModel model) {
        MemorySettings current = world.memorySettings();
        return new MemorySettings(model, current.retrievalThreshold(), current.capacity(), current.pruneForgotten(),
                current.similarityThreshold());
    }
}
