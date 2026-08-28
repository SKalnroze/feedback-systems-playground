package dev.fsp.modules.interpersonal;

import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.memory.MemorySettings;
import dev.fsp.engine.spec.ObjectTypeSpec;
import dev.fsp.engine.spec.VariableSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The vocabulary of the interpersonal module: what a person is made of.
 *
 * <p>Six variables, chosen because each one moves on a different timescale and they are not
 * substitutes for one another. Trust responds quickly and recovers slowly; familiarity only ever
 * grows; resentment accumulates and is what memory reactivation feeds. A model with fewer of these
 * collapses distinct dynamics into a single "relationship quality" number and stops being able to
 * show the interesting cases, such as two people who know each other well, quite like each other,
 * and no longer trust each other at all.
 */
public final class Person {

    public static final String TYPE_ID = "person";

    /** Willingness to be vulnerable with the other party. Falls fast, recovers slowly. */
    public static final String TRUST = "trust";

    /** Plain liking, largely independent of trust. */
    public static final String AFFINITY = "affinity";

    /** Accumulated grievance. The variable memory reactivation keeps alive. */
    public static final String RESENTMENT = "resentment";

    /** How well the person knows the others; only ever increases. */
    public static final String FAMILIARITY = "familiarity";

    /** Capacity for interaction. Spent by contact, recovered by quiet. */
    public static final String ENERGY = "energy";

    /** Disposition to engage at all. Falls as resentment rises, which is the withdrawal spiral. */
    public static final String ENGAGEMENT = "engagement";

    /** Stable trait: how readily this person updates their view of others. */
    public static final String OPENNESS = "openness";

    /** Memory kind for something the person took part in. */
    public static final String INTERACTION = "interaction";

    /** Memory kind for something the person watched happen to others. */
    public static final String OBSERVATION = "observation";

    /** Memory kind for something the person was told about, second hand. */
    public static final String HEARSAY = "hearsay";

    private Person() {
    }

    /**
     * Default memory settings for people: power-law forgetting, because human retention has a long
     * tail rather than a clean exponential fade, and a floor of zero so that nothing is
     * indelible unless a spec says so.
     */
    public static MemorySettings defaultMemory() {
        return new MemorySettings(new DecayModel.PowerLaw(0.45, DecayModel.DecayCommon.NONE), 0.04, 400, true, 0.65);
    }

    public static ObjectTypeSpec type() {
        return type(defaultMemory());
    }

    public static ObjectTypeSpec type(MemorySettings memory) {
        return new ObjectTypeSpec(TYPE_ID, "Person",
                List.of(VariableSpec.unitStock(TRUST, 0.5), VariableSpec.signedStock(AFFINITY, 0.1),
                        VariableSpec.unitStock(RESENTMENT, 0.0), VariableSpec.unitStock(FAMILIARITY, 0.1),
                        VariableSpec.unitStock(ENERGY, 0.8), VariableSpec.unitStock(ENGAGEMENT, 0.7),
                        VariableSpec.constant(OPENNESS, 0.5)),
                memory, Set.of(), Map.of());
    }
}
