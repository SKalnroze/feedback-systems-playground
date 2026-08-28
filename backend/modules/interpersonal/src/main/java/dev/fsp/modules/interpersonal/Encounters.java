package dev.fsp.modules.interpersonal;

import dev.fsp.engine.expr.EvalContext.MemoryAggregateQuery;
import dev.fsp.engine.expr.EvalContext.MemoryAggregateQuery.Aggregate;
import dev.fsp.engine.module.InteractionContext;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.state.ObjectState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared machinery for the interaction rules: who meets whom, and what it does to them. */
final class Encounters {

    private Encounters() {
    }

    /**
     * Picks a pair, weighted so that people who already know each other and have the energy to
     * engage are likelier to meet again. Without that weighting every group flattens into uniform
     * mixing and the clustering that makes social systems interesting never appears.
     */
    static List<ObjectState> weightedPair(InteractionContext context, Rng rng) {
        List<ObjectState> people = context.state().objectsOfType(Person.TYPE_ID);
        if (people.size() < 2) {
            return List.of();
        }
        ObjectState initiator = weightedPick(people, rng, person -> person.getOrDefault(Person.ENERGY, 0.5)
                * person.getOrDefault(Person.ENGAGEMENT, 0.5) + 0.05);
        if (initiator == null) {
            return List.of();
        }
        List<ObjectState> others = new ArrayList<>(people);
        others.remove(initiator);
        ObjectState partner = weightedPick(others, rng.derive(1L),
                person -> familiarityWeight(context, initiator, person));
        return partner == null ? List.of() : List.of(initiator, partner);
    }

    private static double familiarityWeight(InteractionContext context, ObjectState initiator, ObjectState candidate) {
        double familiarity = candidate.getOrDefault(Person.FAMILIARITY, 0.1);
        double affinity = memoryTone(context, initiator, candidate);
        // Even a disliked colleague gets a floor: avoidance is never total in a shared setting.
        return Math.max(0.05, 0.2 + familiarity + 0.4 * affinity);
    }

    /** Roulette-wheel selection over non-negative weights. */
    static ObjectState weightedPick(List<ObjectState> candidates, Rng rng,
            java.util.function.ToDoubleFunction<ObjectState> weigher) {
        if (candidates.isEmpty()) {
            return null;
        }
        double total = 0.0;
        double[] weights = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            weights[i] = Math.max(0.0, weigher.applyAsDouble(candidates.get(i)));
            total += weights[i];
        }
        if (total <= 0.0) {
            return candidates.get(rng.nextInt(candidates.size()));
        }
        double roll = rng.nextDouble() * total;
        double cumulative = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += weights[i];
            if (roll < cumulative) {
                return candidates.get(i);
            }
        }
        return candidates.getLast();
    }

    /**
     * How the holder currently feels about the other party, based on their memories rather than
     * on a stored relationship number. This is the point of the whole exercise: attitude is a
     * consequence of what is remembered and how strongly, so forgetting genuinely changes
     * behaviour.
     */
    static double memoryTone(InteractionContext context, ObjectState holder, ObjectState about) {
        return context.state().memories().aggregate(holder.id(), about.id(), context.tick(),
                new MemoryAggregateQuery(Aggregate.MEAN_VALENCE, null, true, 0.0));
    }

    /** Applies the consequences of an encounter to one participant. */
    static void applyOutcome(InteractionContext context, ObjectState person, ObjectState other, double valence,
            double intensity) {
        double openness = person.getOrDefault(Person.OPENNESS, 0.5);
        double learningRate = 0.04 + 0.08 * openness;

        adjust(person, Person.TRUST, valence > 0 ? valence * learningRate : valence * learningRate * 2.0, 0.0, 1.0);
        adjust(person, Person.AFFINITY, valence * learningRate, -1.0, 1.0);
        adjust(person, Person.RESENTMENT, valence < 0 ? -valence * learningRate * 1.5 : -0.01, 0.0, 1.0);
        adjust(person, Person.FAMILIARITY, 0.015 * intensity, 0.0, 1.0);
        adjust(person, Person.ENERGY, -0.02 * intensity, 0.0, 1.0);

        context.markInPlay(person);
    }

    static void adjust(ObjectState person, String variable, double delta, double min, double max) {
        person.set(variable, Math.clamp(person.getOrDefault(variable, 0.0) + delta, min, max));
    }

    /**
     * Lets everyone else present notice. Observation memories are weaker than first-hand ones and
     * are what turn a private disagreement into a reputation.
     */
    static void notifyBystanders(InteractionContext context, ObjectState first, ObjectState second, double valence,
            String[] features, double probability, Rng rng) {
        List<ObjectState> people = context.state().objectsOfType(Person.TYPE_ID);
        int index = 0;
        for (ObjectState bystander : people) {
            index++;
            if (bystander == first || bystander == second) {
                continue;
            }
            if (!rng.derive(index).nextBoolean(probability)) {
                continue;
            }
            // Watching is not the same as taking part: the trace is fainter and less charged.
            context.injectMemory(bystander, first, Person.OBSERVATION, 0.45, valence * 0.6, 0.35,
                    featureMap(features), null);
            Encounters.adjust(bystander, Person.AFFINITY, valence * 0.01, -1.0, 1.0);
        }
    }

    static Map<String, Double> featureMap(String... features) {
        Map<String, Double> map = new java.util.LinkedHashMap<>();
        for (String feature : features) {
            map.put(feature, 1.0);
        }
        return map;
    }
}
