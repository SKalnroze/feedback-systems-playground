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
import dev.fsp.engine.spec.Coupling;
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
 * Systems that only become interesting at population scale.
 *
 * <p>The original presets are small enough to follow person by person, which is the right way to
 * learn what the engine does. These are the opposite: each one is built so that the behaviour worth
 * seeing is a property of the crowd rather than of anybody in it, and could not be produced by
 * watching eight people more carefully.
 */
public final class PopulationPresets {

    private PopulationPresets() {
    }

    public static List<Preset> all() {
        return List.of(rumourMill(), burnoutContagion(), theFewWhoHoldItTogether());
    }

    private static ObjectSpec crowd(String id, int count, double trust, double openness) {
        return new ObjectSpec(id, Person.TYPE_ID, id,
                Map.of(Person.TRUST, trust, Person.OPENNESS, openness), Set.of(), Map.of(), count);
    }

    /**
     * Four hundred people, one rumour, and the question of whether it dies out.
     *
     * <p>Gossip spreads second-hand impressions, and each retelling is a memory in its own right. A
     * rumour therefore competes with forgetting: below some rate of retelling it fades, above it the
     * population's memory of the same event keeps reinforcing itself. The interesting part is that
     * the boundary is sharp - a small change in how often people talk flips the outcome entirely -
     * and that nothing in the specification names a threshold anywhere.
     */
    public static Preset rumourMill() {
        SystemSpec spec = SystemSpec.builder("rumour-mill").name("Rumour mill")
                .description("Four hundred people, occasional gossip, and one accusation that either dies "
                        + "out or becomes common knowledge.")
                .module(InterpersonalModule.ID)
                // A short half-life with no floor: nothing survives here except by being retold.
                .objectType(Person.type(new MemorySettings(DecayModel.Exponential.ofHalfLife(25.0), 0.05, 300, true,
                        0.6)))
                .object(crowd("townsfolk", 400, 0.55, 0.5))
                .globalVariable(new VariableSpec("commonKnowledge", "Common knowledge",
                        VariableSpec.Kind.AUXILIARY, 0.0, 0.0, 1.0))
                // How widely the story is held, read back as one number: the mean strength of what
                // everyone is carrying.
                .link(LinkSpec.of("belief-to-common",
                        new VariableRef.OfType(Person.TYPE_ID, Person.RESENTMENT,
                                VariableRef.OfType.Aggregate.MEAN),
                        new VariableRef.Global("commonKnowledge"), 1.0))
                // ...and common knowledge makes people readier to repeat it, which is the loop.
                .link(new LinkSpec("common-to-engagement", "Everyone is talking about it",
                        new VariableRef.Global("commonKnowledge"),
                        new VariableRef.OfObject("townsfolk", Person.ENGAGEMENT), 0.04, 2,
                        new Transfer.Sigmoid(8.0, 0.25, 1.0), false,
                        Coupling.of(Coupling.Mode.ONE_TO_MANY)))
                .event(EventSpec
                        .of("the-accusation", EventGenerator.FixedSchedule.at(40L),
                                new TargetSelector.RandomK(Person.TYPE_ID, 12),
                                List.of(Effect.AdjustVariable.add(Scope.SELF, Person.RESENTMENT, 0.45)))
                        .limitedTo(1))
                // Hearing it again from someone else revives it, which is what lets a story outlive
                // the memory of any individual who heard it first.
                .trigger(new MemoryTrigger("retold", "Heard it again",
                        new MemoryFilter(Set.of(Person.HEARSAY, Person.INTERACTION), 0.02, 1.0, 3L),
                        new TriggerCondition.All(List.of(TriggerCondition.SubjectPresent.INSTANCE,
                                new TriggerCondition.CueSimilarity(0.5))),
                        new ReactivationEffect(0.05, 1.4, true, -0.01, null, 0.0, null), 4))
                .interaction(InteractionRule.Config.enabled(MeetRule.ID).with("encountersPerTick", 14.0))
                .interaction(InteractionRule.Config.enabled(GossipRule.ID).with("baseProbability", 0.25))
                .interaction(InteractionRule.Config.enabled(ConflictRule.ID))
                .settings(new SimulationSettings(1, 250, 0L, 1, false, 3)).build();

        return new Preset("rumour-mill", "Rumour mill",
                "Watch global.commonKnowledge after tick 40. Twelve people are told something about a "
                        + "stranger; everyone else can only hear it second hand. Either the story burns out "
                        + "within a hundred ticks or it crosses a point where retelling outpaces forgetting "
                        + "and the whole town ends up holding it. Re-run with a different seed: the same "
                        + "model gives both outcomes, which is the finding. The min and max bands on "
                        + "townsfolk.resentment show whether belief is shared or concentrated in a few.",
                spec);
    }

    /**
     * Exhaustion spreading through a workforce that is trying to cover for itself.
     *
     * <p>Everyone's workload is their own, but the team's average exhaustion feeds back into each
     * person's load - because the tired ones do less, and someone picks it up. That single loop is
     * enough to turn a manageable shock into a collapse, and the run shows the moment the average
     * stops recovering between shocks.
     */
    public static Preset burnoutContagion() {
        SystemSpec spec = SystemSpec.builder("burnout-contagion").name("Burnout contagion")
                .description("Three hundred colleagues covering for each other, and a workload that "
                        + "redistributes itself onto whoever is still standing.")
                .module(InterpersonalModule.ID)
                .objectType(Person.type())
                .object(crowd("team", 300, 0.6, 0.5))
                .globalVariable(VariableSpec.unitStock("workload", 0.35))
                .globalVariable(new VariableSpec("exhaustion", "Team exhaustion", VariableSpec.Kind.AUXILIARY,
                        0.0, 0.0, 1.0))
                // Each member's own energy drains under the shared workload: one value to everyone.
                .link(new LinkSpec("workload-drains", "Workload drains everyone",
                        new VariableRef.Global("workload"),
                        new VariableRef.OfObject("team", Person.ENERGY), -0.03, 0, Transfer.linear(), false,
                        Coupling.of(Coupling.Mode.ONE_TO_MANY)))
                // The team's exhaustion is the inverse of its mean energy, gathered from every member.
                .link(new LinkSpec("energy-to-exhaustion", "Exhaustion is what is left of energy",
                        new VariableRef.OfObject("team", Person.ENERGY),
                        new VariableRef.Global("exhaustion"), -1.0, 0, Transfer.linear(), false,
                        Coupling.of(Coupling.Mode.MANY_TO_ONE)))
                // ...and the closing edge: the more exhausted the team, the heavier the load on each
                // person still working. Delayed, because cover is arranged, not instant.
                .link(new LinkSpec("exhaustion-reloads", "The tired ones are covered for",
                        new VariableRef.Global("exhaustion"), new VariableRef.Global("workload"), 0.05, 6,
                        new Transfer.Sigmoid(7.0, 0.45, 1.0), false))
                .event(EventSpec
                        .of("quarter-end", new EventGenerator.Periodic(90, 6, 60),
                                TargetSelector.GlobalOnly.INSTANCE,
                                List.of(new Effect.SetVariable(Scope.GLOBAL, "workload", NumExpr.of(0.85)))))
                .interaction(InteractionRule.Config.enabled(MeetRule.ID).with("encountersPerTick", 10.0))
                .interaction(InteractionRule.Config.enabled(FavourRule.ID).with("baseProbability", 0.06))
                .interaction(InteractionRule.Config.enabled(ConflictRule.ID))
                .settings(new SimulationSettings(1, 250, 0L, 1, false, 4)).build();

        return new Preset("burnout-contagion", "Burnout contagion",
                "Chart global.workload against team.energy.mean. The quarter-end spike hits every 90 ticks "
                        + "and the team recovers from the first one or two. Watch for the spike where the mean "
                        + "no longer returns to where it started: after that the covering loop is supplying "
                        + "more load than the shock did, and workload keeps climbing on its own. The gap "
                        + "between team.energy.min and team.energy.max tells you whether the cost is being "
                        + "shared or dumped on a few people.",
                spec);
    }

    /**
     * A group held together by a handful of people, and what happens when one of them leaves.
     *
     * <p>The connectors are ordinary members with unusually high openness, so they meet more people
     * and accumulate the familiarity that makes future meetings likely. Removing one is a small
     * change by any measure of the specification, and a large one in the run.
     */
    public static Preset theFewWhoHoldItTogether() {
        SystemSpec spec = SystemSpec.builder("connectors").name("The few who hold it together")
                .description("Two hundred acquaintances and six unusually sociable people, one of whom "
                        + "leaves at tick 150.")
                .module(InterpersonalModule.ID)
                .objectType(Person.type(new MemorySettings(DecayModel.PowerLaw.ofExponent(0.5), 0.04, 400, true, 0.65)))
                .object(crowd("members", 200, 0.5, 0.35))
                // Six connectors, authored as their own group so they can be charted apart from the
                // crowd and so one of them can be singled out by the departure event.
                .object(crowd("connectors", 6, 0.6, 0.95))
                .globalVariable(new VariableSpec("cohesion", "Cohesion", VariableSpec.Kind.AUXILIARY, 0.0, 0.0,
                        1.0))
                .link(new LinkSpec("familiarity-to-cohesion", "Cohesion is shared familiarity",
                        new VariableRef.OfObject("members", Person.FAMILIARITY),
                        new VariableRef.Global("cohesion"), 1.0, 0, Transfer.linear(), false,
                        Coupling.of(Coupling.Mode.MANY_TO_ONE)))
                // Each connector's engagement lifts one ordinary member's engagement: a random
                // pairing redrawn every tick, which is what being sociable looks like from the
                // inside of a crowd.
                .link(new LinkSpec("connectors-lift", "Connectors draw people in",
                        new VariableRef.OfObject("connectors", Person.ENGAGEMENT),
                        new VariableRef.OfObject("members", Person.ENGAGEMENT), 0.02, 0, Transfer.linear(), false,
                        Coupling.of(Coupling.Mode.MANY_TO_MANY_RANDOM)))
                // Every member's engagement leaks away at a rate proportional to itself, so the
                // group settles where the connectors' pull balances the drift rather than pinning
                // at the ceiling. Without this the lift saturates within fifty ticks and losing a
                // connector changes nothing you can see - the preset would promise an effect the
                // arithmetic had already hidden.
                .link(new LinkSpec("engagement-drifts", "Engagement drifts down without contact",
                        new VariableRef.OfObject("members", Person.ENGAGEMENT),
                        new VariableRef.OfObject("members", Person.ENGAGEMENT), -0.022, 0, Transfer.linear(),
                        false, Coupling.of(Coupling.Mode.ONE_TO_ONE)))
                // Named, not random: RandomK selects by object *type*, so asking it for one
                // "connectors" would match nothing at all and the event would fire into an empty
                // set - which is exactly what it did until a test noticed the group never reacted.
                // A group's members are addressable individually, which is what makes singling one
                // out possible.
                .event(EventSpec
                        .of("one-connector-leaves", EventGenerator.FixedSchedule.at(150L),
                                new TargetSelector.Specific(Set.of("connectors#1")),
                                List.of(Effect.AdjustVariable.add(Scope.SELF, Person.ENGAGEMENT, -0.9),
                                        Effect.AdjustVariable.add(Scope.SELF, Person.ENERGY, -0.9)))
                        .limitedTo(1))
                .interaction(InteractionRule.Config.enabled(MeetRule.ID).with("encountersPerTick", 12.0))
                .interaction(InteractionRule.Config.enabled(GossipRule.ID))
                .interaction(InteractionRule.Config.enabled(FavourRule.ID))
                .settings(new SimulationSettings(1, 250, 0L, 1, false, 6)).build();

        return new Preset("connectors", "The few who hold it together",
                "Run to 150 and note where global.cohesion and members.engagement.mean have settled. At "
                        + "tick 150 exactly one of the six connectors goes quiet - one person out of two "
                        + "hundred and six. Watch whether the group's mean engagement recovers or steps down "
                        + "to a lower level and stays there. Fork the run just before tick 150 to compare the "
                        + "two futures side by side on the Compare page; that is the clearest way to see how "
                        + "much of the group was resting on one member.",
                spec);
    }
}
