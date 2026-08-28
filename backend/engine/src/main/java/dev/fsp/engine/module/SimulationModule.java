package dev.fsp.engine.module;

import dev.fsp.engine.spec.EventSpec;
import dev.fsp.engine.spec.ObjectTypeSpec;
import dev.fsp.engine.spec.SystemSpec;
import java.util.List;

/**
 * A domain pack: the object types, behaviours, event templates and ready-made systems for one
 * subject area.
 *
 * <p>Interpersonal relationships are the first of these, but nothing in the engine knows that.
 * A module contributes vocabulary and rules; the engine only knows about objects, variables,
 * memories and events.
 *
 * <p>Discovered with {@link java.util.ServiceLoader}, so a new pack is a jar on the classpath.
 */
public interface SimulationModule {

    String id();

    default String label() {
        return id();
    }

    default String description() {
        return "";
    }

    /** Object types this module defines, offered in the editor palette. */
    default List<ObjectTypeSpec> objectTypes() {
        return List.of();
    }

    /** Behaviours objects of those types can perform. */
    default List<InteractionRule> interactionRules() {
        return List.of();
    }

    /** Pre-built events an author can drop into a system and adjust. */
    default List<EventSpec> eventTemplates() {
        return List.of();
    }

    /** Complete, runnable systems demonstrating the module. */
    default List<Preset> presets() {
        return List.of();
    }

    /** A complete system an author can load and then modify. */
    record Preset(String id, String label, String description, SystemSpec spec) {

        public Preset {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("preset id must not be blank");
            }
            label = label == null || label.isBlank() ? id : label;
        }
    }
}
