package dev.fsp.modules.interpersonal;

import dev.fsp.engine.module.InteractionContext;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;
import dev.fsp.engine.state.ObjectState;
import java.util.ArrayList;
import java.util.List;

/**
 * Someone goes out of their way for someone else.
 *
 * <p>The counterweight to {@link ConflictRule}. Favours cost the giver energy, which is what stops
 * a group from simply spiralling upward into universal goodwill: generosity is finite, and a
 * depleted person stops offering it.
 */
public final class FavourRule implements InteractionRule {

    public static final String ID = "favour";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Favours";
    }

    @Override
    public String description() {
        return "People with energy to spare help those they like, at a cost to themselves.";
    }

    @Override
    public List<Parameter> parameters() {
        return List.of(new Parameter("baseProbability", "Chance of offering a favour", 0.05, 0.0, 1.0),
                new Parameter("energyCost", "Energy spent by the giver", 0.08, 0.0, 1.0),
                new Parameter("warmth", "How much a favour is worth", 0.6, 0.0, 1.0),
                new Parameter("observationProbability", "Chance a bystander notices", 0.2, 0.0, 1.0));
    }

    @Override
    public void execute(InteractionContext context) {
        List<ObjectState> people = context.state().objectsOfType(Person.TYPE_ID);
        if (people.size() < 2) {
            return;
        }
        double baseProbability = context.param("baseProbability", 0.05);
        double energyCost = context.param("energyCost", 0.08);
        double warmth = context.param("warmth", 0.6);
        double observationProbability = context.param("observationProbability", 0.2);

        Rng rng = context.rng(RngStream.INTERACTION_SELECTION);
        int index = 0;
        for (ObjectState giver : people) {
            index++;
            double energy = giver.getOrDefault(Person.ENERGY, 0.5);
            double probability = Math.clamp(baseProbability * energy * 2.0, 0.0, 1.0);
            if (!rng.derive(index).nextBoolean(probability)) {
                continue;
            }
            List<ObjectState> others = new ArrayList<>(people);
            others.remove(giver);
            ObjectState receiver = Encounters.weightedPick(others, rng.derive(3_000 + index),
                    candidate -> Math.max(0.02, 0.3 + Encounters.memoryTone(context, giver, candidate)));
            if (receiver == null) {
                continue;
            }

            context.addCue("favour", 1.0);
            context.addCue("warm", 1.0);

            // Asymmetric on purpose: being helped moves you more than helping does.
            Encounters.applyOutcome(context, receiver, giver, warmth, 1.0);
            Encounters.applyOutcome(context, giver, receiver, warmth * 0.4, 0.5);
            Encounters.adjust(giver, Person.ENERGY, -energyCost, 0.0, 1.0);
            Encounters.adjust(receiver, Person.ENGAGEMENT, 0.03, 0.0, 1.0);

            context.injectMemory(receiver, giver, Person.INTERACTION, 0.8, warmth, 0.7,
                    Encounters.featureMap("favour", "warm"), null);
            context.injectMemory(giver, receiver, Person.INTERACTION, 0.5, warmth * 0.5, 0.4,
                    Encounters.featureMap("favour", "warm"), null);
            Encounters.notifyBystanders(context, giver, receiver, warmth, new String[] {"favour", "warm"},
                    observationProbability, rng.derive(4_000 + index));

            context.log(giver.id() + " did " + receiver.id() + " a favour");
        }
    }
}
