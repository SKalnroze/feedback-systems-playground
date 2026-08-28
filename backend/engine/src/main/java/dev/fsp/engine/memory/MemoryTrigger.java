package dev.fsp.engine.memory;

import java.util.Set;

/**
 * A rule pairing "what brings a memory back" with "what that does to it".
 *
 * @param id           stable id, used in the audit log so a strength jump can be traced to its cause
 * @param label        display name
 * @param filter       which memories this trigger considers at all
 * @param condition    when it fires
 * @param effect       what firing does
 * @param maxPerTick   cap on reactivations per owner per tick, 0 for unlimited; stops a single
 *                     dramatic event from rehearsing an entire lifetime of memories at once
 */
public record MemoryTrigger(String id, String label, MemoryFilter filter, TriggerCondition condition,
        ReactivationEffect effect, int maxPerTick) {

    public MemoryTrigger {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("trigger id must not be blank");
        }
        if (maxPerTick < 0) {
            throw new IllegalArgumentException("maxPerTick must not be negative: " + maxPerTick);
        }
        if (condition == null || effect == null || filter == null) {
            throw new IllegalArgumentException("filter, condition and effect are all required");
        }
    }

    public boolean hasPerTickLimit() {
        return maxPerTick > 0;
    }

    /**
     * Narrows the memories a trigger looks at, before its condition is evaluated.
     *
     * @param kinds       memory kinds to consider; empty means all kinds
     * @param minStrength ignore memories weaker than this
     * @param maxStrength ignore memories stronger than this
     * @param minAge      ignore memories younger than this many ticks
     */
    public record MemoryFilter(Set<String> kinds, double minStrength, double maxStrength, long minAge) {

        public static final MemoryFilter ANY = new MemoryFilter(Set.of(), 0.0, 1.0, 0L);

        public MemoryFilter {
            kinds = Set.copyOf(kinds);
            if (minStrength > maxStrength) {
                throw new IllegalArgumentException("minStrength must not exceed maxStrength");
            }
            if (minAge < 0L) {
                throw new IllegalArgumentException("minAge must not be negative: " + minAge);
            }
        }

        public static MemoryFilter ofKinds(String... kinds) {
            return new MemoryFilter(Set.of(kinds), 0.0, 1.0, 0L);
        }

        public boolean accepts(MemoryRecord memory, long tick) {
            if (!kinds.isEmpty() && !kinds.contains(memory.kind())) {
                return false;
            }
            if (tick - memory.createdTick() < minAge) {
                return false;
            }
            double strength = memory.strengthAt(tick);
            return strength >= minStrength && strength <= maxStrength;
        }
    }

    /**
     * What reactivation does to a memory.
     *
     * @param strengthBoost   added to effective initial strength (consolidation)
     * @param stabilityGain   added to stability, slowing later decay (the spacing effect)
     * @param resetClock      restart the decay clock from now
     * @param valenceShift    move how the memory feels; rumination sours, reappraisal softens
     * @param spawnDerived    when set, lay down a new memory of this kind cued by the retrieval,
     *                        modelling how recalling something can itself become an event
     * @param derivedStrength initial strength of that derived memory, as a fraction of the original
     * @param emitEventId     optional event to fire, letting recall feed back into the world
     */
    public record ReactivationEffect(double strengthBoost, double stabilityGain, boolean resetClock,
            double valenceShift, String spawnDerived, double derivedStrength, String emitEventId) {

        public static final ReactivationEffect REHEARSE = new ReactivationEffect(0.05, 1.0, true, 0.0, null, 0.0, null);

        public ReactivationEffect {
            if (strengthBoost < 0.0) {
                throw new IllegalArgumentException("strengthBoost must not be negative: " + strengthBoost);
            }
            if (stabilityGain < 0.0) {
                throw new IllegalArgumentException("stabilityGain must not be negative: " + stabilityGain);
            }
            if (derivedStrength < 0.0 || derivedStrength > 1.0) {
                throw new IllegalArgumentException("derivedStrength must be within [0, 1]: " + derivedStrength);
            }
        }

        public boolean spawnsDerivedMemory() {
            return spawnDerived != null && !spawnDerived.isBlank();
        }

        public boolean emitsEvent() {
            return emitEventId != null && !emitEventId.isBlank();
        }

        /** The history entry this effect writes onto the memory. */
        public Reactivation toReactivation(long tick, String triggerId) {
            return new Reactivation(tick, strengthBoost, stabilityGain, resetClock, triggerId);
        }
    }
}
