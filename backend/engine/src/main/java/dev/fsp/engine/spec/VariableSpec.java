package dev.fsp.engine.spec;

/**
 * Declaration of one variable.
 *
 * @param name    identifier, unique within its owning type or the global scope
 * @param label   display name
 * @param kind    how the variable behaves under the link graph
 * @param initial starting value
 * @param min     lower bound, enforced after every write
 * @param max     upper bound, enforced after every write
 */
public record VariableSpec(String name, String label, Kind kind, double initial, double min, double max) {

    /** What a variable does between ticks. */
    public enum Kind {

        /**
         * Accumulates. Incoming link contributions are added to its current value, and it keeps
         * whatever it has until something changes it. Trust, resentment, fatigue.
         */
        STOCK,

        /**
         * Recomputed from its inputs every tick, keeping no history of its own. Derived readings
         * such as "current tension", which should not drift when its inputs stop arriving.
         */
        AUXILIARY,

        /** Never changes. Dials the author sets to parameterise a run. */
        CONSTANT
    }

    public VariableSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("variable name must not be blank");
        }
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max for " + name);
        }
        if (initial < min || initial > max) {
            throw new IllegalArgumentException(
                    "initial value " + initial + " for " + name + " lies outside [" + min + ", " + max + "]");
        }
        label = label == null || label.isBlank() ? name : label;
    }

    /** A unit-interval stock, the common case for a relationship variable. */
    public static VariableSpec unitStock(String name, double initial) {
        return new VariableSpec(name, name, Kind.STOCK, initial, 0.0, 1.0);
    }

    /** A signed unit stock, for variables with a natural neutral point. */
    public static VariableSpec signedStock(String name, double initial) {
        return new VariableSpec(name, name, Kind.STOCK, initial, -1.0, 1.0);
    }

    public static VariableSpec constant(String name, double value) {
        return new VariableSpec(name, name, Kind.CONSTANT, value, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    public double clamp(double value) {
        return Math.clamp(value, min, max);
    }

    public boolean isWritable() {
        return kind != Kind.CONSTANT;
    }
}
