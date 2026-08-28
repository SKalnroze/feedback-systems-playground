package dev.fsp.modules.interpersonal;

import dev.fsp.engine.memory.MemoryRecord;
import dev.fsp.engine.module.InteractionContext;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.rng.RngStream;
import dev.fsp.engine.state.ObjectState;
import java.util.ArrayList;
import java.util.List;

/**
 * One person tells another what they think of a third.
 *
 * <p>The reason the module models memory rather than a relationship matrix. A hearsay memory is a
 * copy of someone else's memory, weakened by the retelling and coloured by the teller's own
 * feeling, and it then decays on the listener's terms. That produces things a pairwise model
 * cannot: reputations that outlive the events behind them, and second-hand grudges between people
 * who have barely met.
 */
public final class GossipRule implements InteractionRule {

    public static final String ID = "gossip";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Gossip";
    }

    @Override
    public String description() {
        return "People pass on what they think of others, creating second-hand impressions.";
    }

    @Override
    public List<Parameter> parameters() {
        return List.of(new Parameter("probability", "Chance someone gossips this tick", 0.06, 0.0, 1.0),
                new Parameter("fidelity", "How much survives the retelling", 0.5, 0.0, 1.0),
                new Parameter("minStrength", "How vivid a memory must be to be worth telling", 0.3, 0.0, 1.0));
    }

    @Override
    public void execute(InteractionContext context) {
        List<ObjectState> people = context.state().objectsOfType(Person.TYPE_ID);
        if (people.size() < 3) {
            return;
        }
        double probability = context.param("probability", 0.06);
        double fidelity = context.param("fidelity", 0.5);
        double minStrength = context.param("minStrength", 0.3);

        Rng rng = context.rng(RngStream.INTERACTION_SELECTION);
        int index = 0;
        for (ObjectState teller : people) {
            index++;
            if (!rng.derive(index).nextBoolean(probability)) {
                continue;
            }
            MemoryRecord worthTelling = mostTellableMemory(context, teller, minStrength);
            if (worthTelling == null) {
                continue;
            }
            ObjectState subject = context.state().hasObject(worthTelling.subjectId())
                    ? context.state().object(worthTelling.subjectId())
                    : null;
            if (subject == null) {
                continue;
            }

            List<ObjectState> listeners = new ArrayList<>(people);
            listeners.remove(teller);
            listeners.remove(subject);
            if (listeners.isEmpty()) {
                continue;
            }
            ObjectState listener = Encounters.weightedPick(listeners, rng.derive(5_000 + index),
                    candidate -> Math.max(0.02, 0.3 + Encounters.memoryTone(context, teller, candidate)));
            if (listener == null) {
                continue;
            }

            context.addCue("gossip", 1.0);
            double strength = worthTelling.strengthAt(context.tick()) * fidelity;
            double valence = worthTelling.valence() * fidelity;

            context.injectMemory(listener, subject, Person.HEARSAY, strength, valence, 0.3,
                    Encounters.featureMap("gossip", valence >= 0 ? "warm" : "strained"), null);
            // Hearsay shifts how the listener feels about someone they may hardly know.
            Encounters.adjust(listener, Person.AFFINITY, valence * 0.02, -1.0, 1.0);
            Encounters.adjust(listener, Person.TRUST, valence * 0.015, 0.0, 1.0);
            // Sharing a confidence draws teller and listener closer, whatever it does to the subject.
            Encounters.adjust(listener, Person.FAMILIARITY, 0.01, 0.0, 1.0);
            Encounters.adjust(teller, Person.FAMILIARITY, 0.01, 0.0, 1.0);
            context.markInPlay(teller);
            context.markInPlay(listener);

            context.log(teller.id() + " told " + listener.id() + " about " + subject.id());
        }
    }

    /** The most vivid memory the teller holds about anyone else, if it clears the bar. */
    private static MemoryRecord mostTellableMemory(InteractionContext context, ObjectState teller,
            double minStrength) {
        MemoryRecord best = null;
        double bestScore = minStrength;
        for (MemoryRecord memory : context.state().memories().of(teller.id())) {
            if (memory.subjectId().equals(teller.id()) || Person.HEARSAY.equals(memory.kind())) {
                continue;
            }
            // Vividness times charge: nobody passes on a faint, neutral recollection.
            double score = memory.strengthAt(context.tick()) * (0.4 + Math.abs(memory.valence()));
            if (score > bestScore) {
                bestScore = score;
                best = memory;
            }
        }
        return best;
    }
}
