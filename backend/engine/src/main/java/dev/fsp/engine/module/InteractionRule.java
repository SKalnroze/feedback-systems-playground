package dev.fsp.engine.module;

import java.util.List;
import java.util.Map;

/**
 * A behaviour that objects perform on their own, without an external event prompting it.
 *
 * <p>This is the agent half of the hybrid model: events arrive from outside, links propagate
 * between variables, and interaction rules are what the objects do among themselves.
 */
public interface InteractionRule {

    String id();

    default String label() {
        return id();
    }

    default String description() {
        return "";
    }

    /** Parameters the rule understands, with their defaults, so the editor can render controls. */
    default List<Parameter> parameters() {
        return List.of();
    }

    /** Runs the rule for one tick. */
    void execute(InteractionContext context);

    /**
     * @param name     parameter key used in the spec
     * @param label    display name
     * @param fallback value used when the spec does not set it
     * @param min      lower bound for editor controls
     * @param max      upper bound for editor controls
     */
    record Parameter(String name, String label, double fallback, double min, double max) {

        public static Parameter of(String name, double fallback, double min, double max) {
            return new Parameter(name, name, fallback, min, max);
        }
    }

    /**
     * How a rule is configured in a system.
     *
     * @param weight relative frequency when several rules compete for the same interaction slot
     */
    record Config(String ruleId, boolean enabled, double weight, Map<String, Double> params) {

        public Config {
            params = Map.copyOf(params);
            if (weight < 0.0) {
                throw new IllegalArgumentException("weight must not be negative for rule " + ruleId);
            }
        }

        public static Config enabled(String ruleId) {
            return new Config(ruleId, true, 1.0, Map.of());
        }

        public Config with(String name, double value) {
            Map<String, Double> merged = new java.util.LinkedHashMap<>(params);
            merged.put(name, value);
            return new Config(ruleId, enabled, weight, merged);
        }

        public Config weighted(double newWeight) {
            return new Config(ruleId, enabled, newWeight, params);
        }
    }
}
