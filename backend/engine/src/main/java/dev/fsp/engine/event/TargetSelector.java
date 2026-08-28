package dev.fsp.engine.event;

import dev.fsp.engine.expr.Predicate;
import dev.fsp.engine.rng.Rng;
import dev.fsp.engine.state.BoundEvalContext;
import dev.fsp.engine.state.ObjectState;
import dev.fsp.engine.state.SimulationState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides who an event happens to.
 *
 * <p>Separating "how often" ({@link EventGenerator}) from "to whom" keeps both reusable: the same
 * Poisson rate can hit one random person, everyone at once, or only those who already resent each
 * other, and the difference between those three is usually the whole point of the experiment.
 */
public sealed interface TargetSelector {

    String kind();

    /** Objects the event applies to this occurrence, in a deterministic order. */
    List<Target> select(SimulationState state, Rng rng);

    /**
     * One application of an event.
     *
     * @param primary   the object the effects treat as SELF, or null for a purely global event
     * @param secondary the counterpart bound to TARGET, when the event is about a pair
     */
    record Target(ObjectState primary, ObjectState secondary) {

        public static Target of(ObjectState primary) {
            return new Target(primary, null);
        }

        public static final Target GLOBAL = new Target(null, null);

        public boolean isPair() {
            return primary != null && secondary != null;
        }
    }

    /** The event has no object target; its effects touch global variables only. */
    record GlobalOnly() implements TargetSelector {

        public static final GlobalOnly INSTANCE = new GlobalOnly();

        @Override
        public String kind() {
            return "global";
        }

        @Override
        public List<Target> select(SimulationState state, Rng rng) {
            return List.of(Target.GLOBAL);
        }
    }

    /** Everyone, or everyone of one type. A company-wide reorganisation. */
    record Everyone(String typeId) implements TargetSelector {

        public static Everyone all() {
            return new Everyone(null);
        }

        @Override
        public String kind() {
            return "everyone";
        }

        @Override
        public List<Target> select(SimulationState state, Rng rng) {
            return toTargets(candidates(state, typeId));
        }
    }

    /** A random handful. The everyday shape of luck. */
    record RandomK(String typeId, int count) implements TargetSelector {

        public RandomK {
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive: " + count);
            }
        }

        @Override
        public String kind() {
            return "random-k";
        }

        @Override
        public List<Target> select(SimulationState state, Rng rng) {
            return toTargets(rng.sample(candidates(state, typeId), count));
        }
    }

    /** Only objects satisfying a condition, evaluated with the candidate bound to SELF. */
    record Matching(String typeId, Predicate condition) implements TargetSelector {

        @Override
        public String kind() {
            return "matching";
        }

        @Override
        public List<Target> select(SimulationState state, Rng rng) {
            List<ObjectState> matches = new ArrayList<>();
            for (ObjectState candidate : candidates(state, typeId)) {
                if (condition.test(BoundEvalContext.forObject(state, candidate))) {
                    matches.add(candidate);
                }
            }
            return toTargets(matches);
        }
    }

    /** Named objects, for scripted interventions aimed at a particular person. */
    record Specific(Set<String> objectIds) implements TargetSelector {

        public Specific {
            objectIds = Set.copyOf(objectIds);
        }

        @Override
        public String kind() {
            return "specific";
        }

        @Override
        public List<Target> select(SimulationState state, Rng rng) {
            List<ObjectState> matches = new ArrayList<>();
            // Iterate state order, not set order, so the result does not depend on hashing.
            for (ObjectState object : state.activeObjects()) {
                if (objectIds.contains(object.id())) {
                    matches.add(object);
                }
            }
            return toTargets(matches);
        }
    }

    /**
     * A random ordered pair of distinct objects. The basic unit for anything relational: an
     * argument, a favour, a conversation someone else overhears.
     */
    record RandomPair(String typeId, int pairs) implements TargetSelector {

        public RandomPair {
            if (pairs <= 0) {
                throw new IllegalArgumentException("pairs must be positive: " + pairs);
            }
        }

        public static RandomPair one() {
            return new RandomPair(null, 1);
        }

        @Override
        public String kind() {
            return "random-pair";
        }

        @Override
        public List<Target> select(SimulationState state, Rng rng) {
            List<ObjectState> pool = candidates(state, typeId);
            if (pool.size() < 2) {
                return List.of();
            }
            List<Target> targets = new ArrayList<>(pairs);
            for (int i = 0; i < pairs; i++) {
                List<ObjectState> pair = rng.derive(i).sample(pool, 2);
                targets.add(new Target(pair.get(0), pair.get(1)));
            }
            return List.copyOf(targets);
        }
    }

    /**
     * Two named objects, in a fixed order. The scripted counterpart of {@link RandomPair}: for
     * staging a specific incident between specific people at a specific tick.
     */
    record NamedPair(String primaryId, String secondaryId) implements TargetSelector {

        public NamedPair {
            if (primaryId == null || secondaryId == null || primaryId.equals(secondaryId)) {
                throw new IllegalArgumentException("a pair needs two different objects");
            }
        }

        @Override
        public String kind() {
            return "named-pair";
        }

        @Override
        public List<Target> select(SimulationState state, Rng rng) {
            if (!state.hasObject(primaryId) || !state.hasObject(secondaryId)) {
                return List.of();
            }
            ObjectState primary = state.object(primaryId);
            ObjectState secondary = state.object(secondaryId);
            if (!primary.isActive() || !secondary.isActive()) {
                return List.of();
            }
            return List.of(new Target(primary, secondary));
        }
    }

    /** Every ordered pair within a type. Expensive, but exact for small groups. */
    record AllPairs(String typeId) implements TargetSelector {

        @Override
        public String kind() {
            return "all-pairs";
        }

        @Override
        public List<Target> select(SimulationState state, Rng rng) {
            List<ObjectState> pool = candidates(state, typeId);
            List<Target> targets = new ArrayList<>();
            for (ObjectState first : pool) {
                for (ObjectState second : pool) {
                    if (first != second) {
                        targets.add(new Target(first, second));
                    }
                }
            }
            return List.copyOf(targets);
        }
    }

    private static List<ObjectState> candidates(SimulationState state, String typeId) {
        return typeId == null ? state.activeObjects() : state.objectsOfType(typeId);
    }

    private static List<Target> toTargets(List<ObjectState> objects) {
        List<Target> targets = new ArrayList<>(objects.size());
        for (ObjectState object : objects) {
            targets.add(Target.of(object));
        }
        return List.copyOf(targets);
    }
}
