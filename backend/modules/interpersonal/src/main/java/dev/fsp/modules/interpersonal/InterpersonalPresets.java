package dev.fsp.modules.interpersonal;

import dev.fsp.engine.event.Effect;
import dev.fsp.engine.event.EventGenerator;
import dev.fsp.engine.event.TargetSelector;
import dev.fsp.engine.expr.NumExpr;
import dev.fsp.engine.expr.Scope;
import dev.fsp.engine.graph.Transfer;
import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.memory.MemorySettings;
import dev.fsp.engine.memory.MemoryTrigger;
import dev.fsp.engine.memory.MemoryTrigger.MemoryFilter;
import dev.fsp.engine.memory.MemoryTrigger.ReactivationEffect;
import dev.fsp.engine.memory.TriggerCondition;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.module.SimulationModule.Preset;
import dev.fsp.engine.spec.EventSpec;
import dev.fsp.engine.spec.LinkSpec;
import dev.fsp.engine.spec.ObjectSpec;
import dev.fsp.engine.spec.SimulationSettings;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.spec.VariableRef;
import dev.fsp.engine.spec.VariableSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ready-made systems. Each one is set up to make a particular dynamic visible within a few hundred
 * ticks, so that opening the tool for the first time shows something worth looking at rather than
 * an empty canvas.
 */
public final class InterpersonalPresets {

    private InterpersonalPresets() {
    }

    public static List<Preset> all() {
        return List.of(officeTeam(), friendGroupDrift(), newArrivalIntegration(), familyUnitWithGrudges());
    }

    /**
     * Eight colleagues, ordinary contact, occasional friction, and a workload shock partway
     * through. The default demonstration: a group that is fine until it is put under pressure.
     */
    public static Preset officeTeam() {
        SystemSpec spec = SystemSpec.builder("office-team-8").name("Office team of eight")
                .description("Eight colleagues under a workload shock, with gossip and grudges.")
                .module(InterpersonalModule.ID).objectType(Person.type())
                .object(person("ana", 0.6, 0.6)).object(person("ben", 0.5, 0.5)).object(person("chi", 0.55, 0.4))
                .object(person("dev", 0.45, 0.7)).object(person("eve", 0.6, 0.3)).object(person("fay", 0.4, 0.6))
                .object(person("gus", 0.5, 0.5)).object(person("hal", 0.35, 0.45))
                .globalVariable(VariableSpec.unitStock("workload", 0.3))
                .globalVariable(new VariableSpec("teamTension", "Team tension", VariableSpec.Kind.AUXILIARY, 0.0, 0.0,
                        1.0))
                // Group-level tension is the average grievance, read back as a single number.
                .link(LinkSpec.of("resentment-to-tension",
                        new VariableRef.OfType(Person.TYPE_ID, Person.RESENTMENT,
                                VariableRef.OfType.Aggregate.MEAN),
                        new VariableRef.Global("teamTension"), 1.0))
                // ... which then costs everyone energy, with a delay: pressure tells slowly.
                .link(new LinkSpec("workload-drains-energy", "Workload drains energy",
                        new VariableRef.Global("workload"),
                        new VariableRef.OfObject("ana", Person.ENERGY), -0.02, 3, Transfer.linear(), false))
                .link(new LinkSpec("tension-erodes-engagement", "Tension erodes engagement",
                        new VariableRef.Global("teamTension"),
                        new VariableRef.OfObject("hal", Person.ENGAGEMENT), -0.05, 5,
                        new Transfer.Sigmoid(6.0, 0.3, 1.0), false))
                .event(EventSpec
                        .of("deadline-crunch", EventGenerator.FixedSchedule.at(120L),
                                TargetSelector.GlobalOnly.INSTANCE,
                                List.of(new Effect.SetVariable(Scope.GLOBAL, "workload", NumExpr.of(0.9))))
                        .limitedTo(1))
                .event(EventSpec.of("reorg-rumour", new EventGenerator.Bernoulli(0.01),
                        new TargetSelector.RandomK(Person.TYPE_ID, 3),
                        List.of(Effect.AdjustVariable.add(Scope.SELF, Person.ENGAGEMENT, -0.08),
                                Effect.AdjustVariable.add(Scope.SELF, Person.RESENTMENT, 0.05))))
                // Seeing the person you have a grievance with brings it back, and keeps it alive.
                .trigger(new MemoryTrigger("grudge-resurfaces", "Grudge resurfaces",
                        new MemoryFilter(Set.of(Person.INTERACTION), 0.1, 1.0, 5L),
                        new TriggerCondition.All(List.of(TriggerCondition.SubjectPresent.INSTANCE,
                                new TriggerCondition.CueSimilarity(0.7))),
                        new ReactivationEffect(0.03, 1.5, true, -0.02, null, 0.0, null), 3))
                .interaction(InteractionRule.Config.enabled(MeetRule.ID).with("encountersPerTick", 3.0))
                .interaction(InteractionRule.Config.enabled(ConflictRule.ID))
                .interaction(InteractionRule.Config.enabled(FavourRule.ID))
                .interaction(InteractionRule.Config.enabled(GossipRule.ID))
                .settings(new SimulationSettings(1, 250, 0L, 1, false)).build();

        return new Preset("office-team-8", "Office team of eight",
                "Eight colleagues, ordinary contact, and a deadline crunch at tick 120.", spec);
    }

    /**
     * A friend group with nothing going wrong: no events at all. Shows what pure decay does to a
     * group that simply sees less of each other over time.
     */
    public static Preset friendGroupDrift() {
        SystemSpec spec = SystemSpec.builder("friend-group-drift").name("Friends drifting apart")
                .description("No shocks, no conflict: only forgetting and dwindling contact.")
                .module(InterpersonalModule.ID)
                // Exponential forgetting with no floor: without rehearsal, nothing survives.
                .objectType(Person.type(new MemorySettings(DecayModel.Exponential.ofHalfLife(40.0), 0.05, 200, true,
                        0.6)))
                .object(person("iris", 0.7, 0.5)).object(person("jon", 0.7, 0.4)).object(person("kit", 0.65, 0.6))
                .object(person("lou", 0.6, 0.35)).object(person("mia", 0.7, 0.5))
                .interaction(InteractionRule.Config.enabled(MeetRule.ID).with("encountersPerTick", 1.0)
                        .with("baseWarmth", 0.25))
                .interaction(InteractionRule.Config.enabled(FavourRule.ID).with("baseProbability", 0.02))
                .settings(new SimulationSettings(1, 250, 0L, 1, false)).build();

        return new Preset("friend-group-drift", "Friends drifting apart",
                "A group with no external events, showing what forgetting alone does.", spec);
    }

    /** Someone joins an established group. Familiarity has to be built from nothing. */
    public static Preset newArrivalIntegration() {
        SystemSpec spec = SystemSpec.builder("new-arrival").name("A newcomer joins")
                .description("An established group of four, plus someone who knows nobody.")
                .module(InterpersonalModule.ID).objectType(Person.type())
                .object(established("nia")).object(established("omar")).object(established("pia"))
                .object(established("raj"))
                .object(new ObjectSpec("sam", Person.TYPE_ID, "sam",
                        Map.of(Person.FAMILIARITY, 0.0, Person.TRUST, 0.4, Person.ENERGY, 0.9), Set.of("newcomer"),
                        Map.of()))
                .event(EventSpec.of("welcome-lunch", EventGenerator.FixedSchedule.at(10L),
                        new TargetSelector.Specific(Set.of("sam")),
                        List.of(Effect.AdjustVariable.add(Scope.SELF, Person.FAMILIARITY, 0.15),
                                Effect.AdjustVariable.add(Scope.SELF, Person.ENGAGEMENT, 0.1))))
                .interaction(InteractionRule.Config.enabled(MeetRule.ID).with("encountersPerTick", 2.0))
                .interaction(InteractionRule.Config.enabled(FavourRule.ID))
                .interaction(InteractionRule.Config.enabled(GossipRule.ID).with("probability", 0.08))
                .settings(new SimulationSettings(1, 250, 0L, 1, false)).build();

        return new Preset("new-arrival", "A newcomer joins",
                "Watch familiarity and reputation build for someone starting from nothing.", spec);
    }

    /**
     * A household where memories never fully fade and a yearly occasion reliably brings the old
     * ones back. The clearest demonstration of reactivation beating decay.
     */
    public static Preset familyUnitWithGrudges() {
        SystemSpec spec = SystemSpec.builder("family-grudges").name("Family with long memories")
                .description("Memories with a permanent floor, and an annual occasion that revives them.")
                .module(InterpersonalModule.ID)
                // ACT-R activation plus a floor: family memories are never quite gone, and every
                // retelling makes them easier to reach again.
                .objectType(Person.type(new MemorySettings(
                        new DecayModel.ActRBaseLevel(0.4, -0.3, 0.35, new DecayModel.DecayCommon(0.08, 0.05)), 0.03,
                        0, false, 0.6)))
                .object(person("tam", 0.6, 0.3)).object(person("uma", 0.55, 0.2)).object(person("vic", 0.5, 0.4))
                .object(person("wren", 0.65, 0.25))
                .event(EventSpec.of("family-gathering", EventGenerator.Periodic.every(60L),
                        TargetSelector.Everyone.all(),
                        List.of(Effect.AdjustVariable.add(Scope.SELF, Person.ENGAGEMENT, 0.1),
                                Effect.AdjustVariable.add(Scope.SELF, Person.ENERGY, -0.05))))
                .trigger(new MemoryTrigger("gathering-revives-everything", "Gatherings revive old memories",
                        new MemoryFilter(Set.of(), 0.0, 1.0, 20L),
                        new TriggerCondition.OnEvent(Set.of("family-gathering")),
                        new ReactivationEffect(0.02, 2.0, false, -0.01, null, 0.0, null), 0))
                .trigger(new MemoryTrigger("quiet-rumination", "Quiet rumination",
                        new MemoryFilter(Set.of(Person.INTERACTION), 0.05, 0.4, 30L),
                        new TriggerCondition.Stochastic(0.01),
                        new ReactivationEffect(0.01, 0.5, false, -0.03, Person.INTERACTION, 0.3, null), 1))
                .interaction(InteractionRule.Config.enabled(MeetRule.ID).with("encountersPerTick", 2.0))
                .interaction(InteractionRule.Config.enabled(ConflictRule.ID).with("baseProbability", 0.02))
                .interaction(InteractionRule.Config.enabled(FavourRule.ID))
                .settings(new SimulationSettings(1, 250, 0L, 1, true)).build();

        return new Preset("family-grudges", "Family with long memories",
                "Memories that never fully fade, revived by a gathering every sixty ticks.", spec);
    }

    private static ObjectSpec person(String id, double trust, double openness) {
        return new ObjectSpec(id, Person.TYPE_ID, id, Map.of(Person.TRUST, trust, Person.OPENNESS, openness), Set.of(),
                Map.of());
    }

    private static ObjectSpec established(String id) {
        return new ObjectSpec(id, Person.TYPE_ID, id, Map.of(Person.FAMILIARITY, 0.7, Person.TRUST, 0.65), Set.of(),
                Map.of());
    }
}
