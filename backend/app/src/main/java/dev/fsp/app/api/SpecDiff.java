package dev.fsp.app.api;

import dev.fsp.engine.memory.MemoryTrigger;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.spec.EventSpec;
import dev.fsp.engine.spec.LinkSpec;
import dev.fsp.engine.spec.ObjectSpec;
import dev.fsp.engine.spec.ObjectTypeSpec;
import dev.fsp.engine.spec.VariableSpec;
import dev.fsp.engine.spec.SystemSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * What changed between two versions of a system.
 *
 * <p>Versions are immutable and every run pins to one, which makes "why did these two runs diverge"
 * a question about the specifications rather than about the runs. Without an answer to it, a
 * version number is just an opaque label.
 *
 * <p>Elements are matched by their stable ids and compared by their rendered form rather than
 * field by field. A structural walk over eight decay models, seven transfer functions and seven
 * generators would be a great deal of code that says the same thing as "this line differs", and it
 * would need extending every time the modelling surface grows.
 */
public final class SpecDiff {

    private SpecDiff() {
    }

    /** One element that was added, removed, or changed between two versions. */
    public record Change(String kind, String id, String change, String before, String after) {
    }

    public record Report(List<Change> changes, boolean identical) {
    }

    public static Report between(SystemSpec before, SystemSpec after) {
        List<Change> changes = new ArrayList<>();

        compare(changes, "object", index(before.objects(), ObjectSpec::id),
                index(after.objects(), ObjectSpec::id), SpecDiff::describeObject);
        compare(changes, "link", index(before.links(), LinkSpec::id),
                index(after.links(), LinkSpec::id), SpecDiff::describeLink);
        compare(changes, "event", index(before.events(), EventSpec::id),
                index(after.events(), EventSpec::id), SpecDiff::describeEvent);
        compare(changes, "trigger", index(before.triggers(), MemoryTrigger::id),
                index(after.triggers(), MemoryTrigger::id), SpecDiff::describeTrigger);
        compare(changes, "object type", index(before.objectTypes(), ObjectTypeSpec::id),
                index(after.objectTypes(), ObjectTypeSpec::id), SpecDiff::describeType);
        compare(changes, "interaction", index(before.interactions(), InteractionRule.Config::ruleId),
                index(after.interactions(), InteractionRule.Config::ruleId), SpecDiff::describeInteraction);

        if (!before.settings().equals(after.settings())) {
            changes.add(new Change("settings", "settings", "changed", String.valueOf(before.settings()),
                    String.valueOf(after.settings())));
        }
        return new Report(changes, changes.isEmpty());
    }

    /**
     * What changed between two versions of one object type.
     *
     * <p>Variables are matched by name and compared whole. A removed variable is the change that
     * actually costs something - every object overriding it, and every link pointing at it, becomes
     * invalid - so it is reported as its own entry rather than folded into a general "type changed".
     */
    public static Report betweenTypes(ObjectTypeSpec before, ObjectTypeSpec after) {
        List<Change> changes = new ArrayList<>();

        compare(changes, "variable", index(before.variables(), VariableSpec::name),
                index(after.variables(), VariableSpec::name), SpecDiff::describeVariable);

        if (!before.memory().equals(after.memory())) {
            changes.add(new Change("memory", before.id(), "changed", String.valueOf(before.memory()),
                    String.valueOf(after.memory())));
        }
        if (!before.defaultTags().equals(after.defaultTags())) {
            changes.add(new Change("tags", before.id(), "changed", String.valueOf(new TreeSet<>(before.defaultTags())),
                    String.valueOf(new TreeSet<>(after.defaultTags()))));
        }
        if (!before.defaultFeatures().equals(after.defaultFeatures())) {
            changes.add(new Change("features", before.id(), "changed",
                    String.valueOf(new java.util.TreeMap<>(before.defaultFeatures())),
                    String.valueOf(new java.util.TreeMap<>(after.defaultFeatures()))));
        }
        if (!before.label().equals(after.label())) {
            changes.add(new Change("label", before.id(), "changed", before.label(), after.label()));
        }
        return new Report(changes, changes.isEmpty());
    }

    private static String describeVariable(VariableSpec variable) {
        return variable.label() + ": " + variable.kind() + " initial=" + variable.initial() + " range=["
                + variable.min() + ", " + variable.max() + "]";
    }

    private static <T> Map<String, T> index(List<T> items, Function<T, String> id) {
        Map<String, T> byId = new LinkedHashMap<>();
        for (T item : items) {
            byId.put(id.apply(item), item);
        }
        return byId;
    }

    private static <T> void compare(List<Change> changes, String kind, Map<String, T> before, Map<String, T> after,
            Function<T, String> describe) {
        Set<String> ids = new LinkedHashSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (String id : ids) {
            T oldOne = before.get(id);
            T newOne = after.get(id);
            if (oldOne == null) {
                changes.add(new Change(kind, id, "added", null, describe.apply(newOne)));
            } else if (newOne == null) {
                changes.add(new Change(kind, id, "removed", describe.apply(oldOne), null));
            } else {
                String a = describe.apply(oldOne);
                String b = describe.apply(newOne);
                if (!a.equals(b)) {
                    changes.add(new Change(kind, id, "changed", a, b));
                }
            }
        }
    }

    private static String describeObject(ObjectSpec object) {
        return object.typeId() + " " + object.label() + " " + new java.util.TreeMap<>(object.variables())
                + " tags=" + new java.util.TreeSet<>(object.tags());
    }

    private static String describeLink(LinkSpec link) {
        return link.label() + ": gain=" + link.gain() + " delay=" + link.delayTicks() + " transfer="
                + link.transfer() + " rate=" + link.usesRate() + " " + link.source() + " -> " + link.target();
    }

    private static String describeEvent(EventSpec event) {
        return event.label() + ": " + event.generator() + " on " + event.selector() + " when " + event.condition()
                + " does " + event.effects() + " cooldown=" + event.cooldownTicks() + " max="
                + event.maxOccurrences();
    }

    private static String describeTrigger(MemoryTrigger trigger) {
        return trigger.label() + ": " + trigger.filter() + " when " + trigger.condition() + " does "
                + trigger.effect() + " maxPerTick=" + trigger.maxPerTick();
    }

    private static String describeType(ObjectTypeSpec type) {
        return type.label() + ": vars=" + type.variables() + " memory=" + type.memory();
    }

    private static String describeInteraction(InteractionRule.Config config) {
        return (config.enabled() ? "enabled" : "disabled") + " weight=" + config.weight() + " params="
                + new java.util.TreeMap<>(config.params());
    }
}
