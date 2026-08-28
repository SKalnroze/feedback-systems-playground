package dev.fsp.modules.interpersonal;

import dev.fsp.engine.module.InteractionContext;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;
import dev.fsp.engine.state.ObjectState;
import java.util.List;

/**
 * Open conflict, which becomes likelier the more grievance has already built up.
 *
 * <p>This is the rule that closes the resentment loop: remembered slights raise the chance of an
 * argument, an argument lays down a strong, sour, highly memorable trace, and that trace feeds the
 * next one. Whether the loop runs away or burns out depends entirely on the decay model and
 * reactivation triggers the author chose, which is precisely the thing worth experimenting with.
 */
public final class ConflictRule implements InteractionRule {

    public static final String ID = "conflict";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Open conflict";
    }

    @Override
    public String description() {
        return "Accumulated resentment occasionally boils over into an argument.";
    }

    @Override
    public List<Parameter> parameters() {
        return List.of(new Parameter("baseProbability", "Chance of conflict with no grievance", 0.01, 0.0, 1.0),
                new Parameter("resentmentWeight", "How much grievance raises the odds", 0.5, 0.0, 5.0),
                new Parameter("severity", "How badly a conflict lands", 0.7, 0.0, 1.0),
                new Parameter("observationProbability", "Chance a bystander notices", 0.6, 0.0, 1.0));
    }

    @Override
    public void execute(InteractionContext context) {
        List<ObjectState> people = context.state().objectsOfType(Person.TYPE_ID);
        if (people.size() < 2) {
            return;
        }
        double baseProbability = context.param("baseProbability", 0.01);
        double resentmentWeight = context.param("resentmentWeight", 0.5);
        double severity = context.param("severity", 0.7);
        double observationProbability = context.param("observationProbability", 0.6);

        Rng rng = context.rng(RngStream.INTERACTION_SELECTION);
        int index = 0;
        for (ObjectState person : people) {
            index++;
            double grievance = person.getOrDefault(Person.RESENTMENT, 0.0);
            double probability = Math.clamp(baseProbability + resentmentWeight * grievance * grievance, 0.0, 1.0);
            if (!rng.derive(index).nextBoolean(probability)) {
                continue;
            }
            ObjectState other = pickGrievanceTarget(context, person, rng.derive(1_000 + index));
            if (other == null) {
                continue;
            }

            double valence = -Math.clamp(0.3 + severity * grievance, 0.0, 1.0);
            context.addCue("conflict", 1.0);
            context.addCue("strained", 1.0);

            for (ObjectState party : List.of(person, other)) {
                ObjectState counterpart = party == person ? other : person;
                Encounters.applyOutcome(context, party, counterpart, valence, 1.5);
                Encounters.adjust(party, Person.ENGAGEMENT, -0.05, 0.0, 1.0);
                context.injectMemory(party, counterpart, Person.INTERACTION, 0.95, valence, 0.9,
                        Encounters.featureMap("conflict", "strained"), null);
            }
            Encounters.notifyBystanders(context, person, other, valence,
                    new String[] {"conflict", "strained"}, observationProbability, rng.derive(2_000 + index));

            context.log(person.id() + " clashed with " + other.id());
        }
    }

    /** Conflict lands on whoever the person already feels worst about, not on a random bystander. */
    private static ObjectState pickGrievanceTarget(InteractionContext context, ObjectState person, Rng rng) {
        List<ObjectState> others = new java.util.ArrayList<>(context.state().objectsOfType(Person.TYPE_ID));
        others.remove(person);
        if (others.isEmpty()) {
            return null;
        }
        return Encounters.weightedPick(others, rng,
                candidate -> Math.max(0.01, -Encounters.memoryTone(context, person, candidate)));
    }
}
