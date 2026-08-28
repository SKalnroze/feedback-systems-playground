package dev.fsp.engine.spec;

/**
 * Points at one variable in the running system.
 *
 * <p>Links are authored between these, which is what lets a macro loop ("team tension raises
 * everyone's stress") sit in the same graph as a per-object one ("Alice's resentment feeds her
 * withdrawal").
 */
public sealed interface VariableRef {

    String kind();

    /** Human-readable key, also used as the metric series key for this variable. */
    String seriesKey();

    /** A system-wide variable belonging to no object. */
    record Global(String name) implements VariableRef {

        public Global {
            requireName(name);
        }

        @Override
        public String kind() {
            return "global";
        }

        @Override
        public String seriesKey() {
            return "global." + name;
        }
    }

    /** One named object's variable. */
    record OfObject(String objectId, String name) implements VariableRef {

        public OfObject {
            requireName(objectId);
            requireName(name);
        }

        @Override
        public String kind() {
            return "object";
        }

        @Override
        public String seriesKey() {
            return objectId + "." + name;
        }
    }

    /**
     * A statistic across every object of a type. Read-only: a link may take its input from an
     * aggregate but may not write to one, since there is no defined way to distribute the change
     * back over the members.
     */
    record OfType(String typeId, String name, Aggregate aggregate) implements VariableRef {

        public enum Aggregate {
            MEAN,
            SUM,
            MIN,
            MAX,
            VARIANCE,
            SPREAD
        }

        public OfType {
            requireName(typeId);
            requireName(name);
        }

        @Override
        public String kind() {
            return "type-aggregate";
        }

        @Override
        public String seriesKey() {
            return aggregate.name().toLowerCase(java.util.Locale.ROOT) + "(" + typeId + "." + name + ")";
        }
    }

    /** True when this reference can be the target of a link. */
    default boolean isWritable() {
        return !(this instanceof OfType);
    }

    private static void requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
