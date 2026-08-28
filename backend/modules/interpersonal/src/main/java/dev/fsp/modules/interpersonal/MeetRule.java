package dev.fsp.modules.interpersonal;

import dev.fsp.engine.module.InteractionContext;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;
import dev.fsp.engine.state.ObjectState;
import java.util.List;

/**
 * Ordinary contact: two people spend time together and it goes somewhere on a scale from pleasant
 * to awkward.
 *
 * <p>The outcome is not drawn from thin air. It starts from what each party already remembers
 * about the other, so a pair with a good history tends to have another good encounter, and a pair
 * carrying grievances tends not to. That is the core feedback loop of the module; everything else
 * modulates it.
 */
public final class MeetRule implements InteractionRule {

    public static final String ID = "meet";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Everyday contact";
    }

    @Override
    public String description() {
        return "Pairs spend time together; how it goes depends on what they remember of each other.";
    }

    @Override
    public List<Parameter> parameters() {
        return List.of(Parameter.of("encountersPerTick", 2.0, 0.0, 20.0),
                new Parameter("observationProbability", "Chance a bystander notices", 0.25, 0.0, 1.0),
                new Parameter("baseWarmth", "Warmth of a neutral encounter", 0.15, -1.0, 1.0),
                new Parameter("volatility", "How much chance affects an encounter", 0.35, 0.0, 1.0));
    }

    @Override
    public void execute(InteractionContext context) {
        int encounters = (int) Math.round(context.param("encountersPerTick", 2.0));
        double observationProbability = context.param("observationProbability", 0.25);
        double baseWarmth = context.param("baseWarmth", 0.15);
        double volatility = context.param("volatility", 0.35);

        for (int i = 0; i < encounters; i++) {
            Rng rng = context.rng(RngStream.INTERACTION_SELECTION).derive(i);
            List<ObjectState> pair = Encounters.weightedPair(context, rng);
            if (pair.isEmpty()) {
                return;
            }
            ObjectState first = pair.get(0);
            ObjectState second = pair.get(1);

            Rng outcomeRng = context.rng(RngStream.INTERACTION_OUTCOME).derive(i);
            double history = (Encounters.memoryTone(context, first, second)
                    + Encounters.memoryTone(context, second, first)) / 2.0;
            double mood = (first.getOrDefault(Person.ENERGY, 0.5) + second.getOrDefault(Person.ENERGY, 0.5)) / 2.0;
            double valence = Math.clamp(
                    baseWarmth + 0.6 * history + 0.3 * (mood - 0.5) + outcomeRng.nextGaussian(0.0, volatility), -1.0,
                    1.0);

            String tone = valence >= 0 ? "warm" : "strained";
            context.addCue("encounter", 1.0);
            context.addCue(tone, 1.0);

            record(context, first, second, valence);
            record(context, second, first, valence);
            Encounters.notifyBystanders(context, first, second, valence, new String[] {"encounter", tone},
                    observationProbability, outcomeRng.derive(99L));

            context.log(first.id() + " and " + second.id() + " met (" + tone + ")");
        }
    }

    private static void record(InteractionContext context, ObjectState person, ObjectState other, double valence) {
        Encounters.applyOutcome(context, person, other, valence, 1.0);
        // Strength tracks how much the encounter stood out, not how pleasant it was: a sharp
        // disagreement and a memorable kindness are both remembered well.
        double salience = 0.3 + 0.5 * Math.abs(valence);
        context.injectMemory(person, other, Person.INTERACTION, salience, valence, salience,
                Encounters.featureMap("encounter", valence >= 0 ? "warm" : "strained"), null);
    }
}
