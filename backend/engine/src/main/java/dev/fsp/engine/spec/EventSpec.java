package dev.fsp.engine.spec;

import dev.fsp.engine.event.Effect;
import dev.fsp.engine.event.EventGenerator;
import dev.fsp.engine.event.TargetSelector;
import dev.fsp.engine.expr.Predicate;
import java.util.List;

/**
 * An external event: how often it happens, who to, and what it does.
 *
 * @param condition     gate evaluated once per target; the event can be made to apply only in
 *                      certain circumstances without changing its rate
 * @param cooldownTicks minimum gap between occurrences, regardless of what the generator says
 * @param maxOccurrences lifetime cap, 0 for unlimited
 */
public record EventSpec(String id, String label, String description, EventGenerator generator,
        TargetSelector selector, Predicate condition, List<Effect> effects, int cooldownTicks, int maxOccurrences) {

    public EventSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("event id must not be blank");
        }
        effects = List.copyOf(effects);
        if (cooldownTicks < 0) {
            throw new IllegalArgumentException("cooldownTicks must not be negative for event " + id);
        }
        if (maxOccurrences < 0) {
            throw new IllegalArgumentException("maxOccurrences must not be negative for event " + id);
        }
        label = label == null || label.isBlank() ? id : label;
        condition = condition == null ? Predicate.always() : condition;
    }

    public static EventSpec of(String id, EventGenerator generator, TargetSelector selector, List<Effect> effects) {
        return new EventSpec(id, id, "", generator, selector, Predicate.always(), effects, 0, 0);
    }

    public EventSpec withCooldown(int ticks) {
        return new EventSpec(id, label, description, generator, selector, condition, effects, ticks, maxOccurrences);
    }

    public EventSpec limitedTo(int occurrences) {
        return new EventSpec(id, label, description, generator, selector, condition, effects, cooldownTicks,
                occurrences);
    }

    public EventSpec onlyWhen(Predicate gate) {
        return new EventSpec(id, label, description, generator, selector, gate, effects, cooldownTicks, maxOccurrences);
    }

    public boolean hasOccurrenceLimit() {
        return maxOccurrences > 0;
    }
}
