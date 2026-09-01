package dev.fsp.engine.spec;

/**
 * How a link connects the members of its endpoints when either end is a group.
 *
 * <p>Between two single objects there is only one thing a link can mean. As soon as an endpoint has
 * a thousand members the question "what does this arrow do" has several defensible answers that
 * produce completely different simulations, and guessing on the author's behalf would make a model
 * whose behaviour cannot be read off its own diagram. So the mode is authored, and the editor
 * explains each one in terms of the link's actual endpoints.
 *
 * @param aggregate how a group is reduced to one number, for the modes that reduce one
 */
public record Coupling(Mode mode, VariableRef.OfType.Aggregate aggregate) {

    /** The modes, in rough order of how much work they do per tick. */
    public enum Mode {

        /**
         * Work it out from the endpoints: two singles pair up, a single feeding a group broadcasts,
         * a group feeding a single is reduced. Every link written before coupling existed loads as
         * this, which is what keeps older systems behaving exactly as they did.
         */
        AUTO,

        /**
         * Member <em>i</em> to member <em>i</em>. A group linked to itself is each member to
         * itself, which is how "everyone's fatigue feeds their own withdrawal" is expressed.
         * Requires the two endpoints to have the same number of members.
         */
        ONE_TO_ONE,

        /** One source value reaches every member of the target group. */
        ONE_TO_MANY,

        /** Every source member is reduced to one number, which reaches a single target. */
        MANY_TO_ONE,

        /** The source group is reduced to one number, which then reaches every target member. */
        MANY_TO_MANY_AGGREGATE,

        /**
         * Each target member is influenced by one randomly chosen source member, redrawn every
         * tick. Seeded from the run, the tick, the link and the member, so it is reproducible.
         */
        MANY_TO_MANY_RANDOM,

        /**
         * Every source member reaches every target member. The honest reading of "many to many",
         * and the expensive one: cost is the product of the two sizes, so the spec validator caps
         * it rather than letting a model quietly stop being runnable.
         */
        MANY_TO_MANY_ALL
    }

    /**
     * Ceiling on source × target for {@link Mode#MANY_TO_MANY_ALL}.
     *
     * <p>A quarter of a million pairs is already a great deal of arithmetic to do sixty times a
     * second; two thousand members on each side would be four million, and the run would appear to
     * hang rather than to be slow.
     */
    public static final long MAX_PAIRS = 250_000L;

    /** The default: infer from the endpoints, reducing groups by their mean where reduction is needed. */
    public static final Coupling AUTO = new Coupling(Mode.AUTO, VariableRef.OfType.Aggregate.MEAN);

    public Coupling {
        if (mode == null) {
            mode = Mode.AUTO;
        }
        if (aggregate == null) {
            aggregate = VariableRef.OfType.Aggregate.MEAN;
        }
    }

    public static Coupling of(Mode mode) {
        return new Coupling(mode, VariableRef.OfType.Aggregate.MEAN);
    }

    public Coupling reducedBy(VariableRef.OfType.Aggregate newAggregate) {
        return new Coupling(mode, newAggregate);
    }

    /** True when this mode reduces a group of source values to a single number. */
    public boolean reduces() {
        return mode == Mode.MANY_TO_ONE || mode == Mode.MANY_TO_MANY_AGGREGATE;
    }

    /**
     * Resolves {@link Mode#AUTO} against the sizes the endpoints actually have.
     *
     * <p>The inference is the conservative one: never all-pairs, never random. Both are deliberate
     * choices with costs, and an author who has not made one should not be given them by default.
     */
    public Coupling resolve(int sourceMembers, int targetMembers) {
        if (mode != Mode.AUTO) {
            return this;
        }
        Mode resolved;
        if (sourceMembers <= 1 && targetMembers <= 1) {
            resolved = Mode.ONE_TO_ONE;
        } else if (sourceMembers <= 1) {
            resolved = Mode.ONE_TO_MANY;
        } else if (targetMembers <= 1) {
            resolved = Mode.MANY_TO_ONE;
        } else if (sourceMembers == targetMembers) {
            // Equal-sized groups almost always mean the same population seen twice, so pairing
            // member to member is both the cheapest reading and the one an author expects.
            resolved = Mode.ONE_TO_ONE;
        } else {
            resolved = Mode.MANY_TO_MANY_AGGREGATE;
        }
        return new Coupling(resolved, aggregate);
    }
}
