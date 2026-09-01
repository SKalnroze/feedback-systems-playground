package dev.fsp.engine.spec;

import dev.fsp.engine.spec.SystemSpec.ValidationIssue;
import java.util.List;
import java.util.Map;

/**
 * The checks that only matter once objects can stand for more than one instance.
 *
 * <p>Separate from {@link SystemSpec}'s own validation because these are the rules an author is
 * most likely to trip over and least likely to guess: how many members are too many, and which
 * couplings actually mean something for a given pair of endpoints. Every message here names the
 * numbers involved, since "invalid coupling" tells nobody what to change.
 */
final class GroupValidation {

    private GroupValidation() {
    }

    static void check(SystemSpec spec, List<ValidationIssue> issues) {
        Map<String, Integer> memberCounts = memberCounts(spec);
        checkCounts(spec, issues);
        checkCouplings(spec, memberCounts, issues);
    }

    /** How many instances each authored object stands for, by id. */
    static Map<String, Integer> memberCounts(SystemSpec spec) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (ObjectSpec object : spec.objects()) {
            counts.put(object.id(), object.count());
        }
        return counts;
    }

    private static void checkCounts(SystemSpec spec, List<ValidationIssue> issues) {
        for (ObjectSpec object : spec.objects()) {
            if (object.count() > ObjectSpec.MAX_MEMBERS) {
                issues.add(ValidationIssue.error("object", object.id(),
                        "asks for " + object.count() + " members; the limit is " + ObjectSpec.MAX_MEMBERS
                                + ", above which a run cannot be checkpointed or charted usefully"));
            } else if (object.count() > ObjectSpec.SOFT_MEMBER_LIMIT) {
                // A warning rather than an error: it will run, but the author should know what it
                // costs before wondering why a tick takes a second.
                issues.add(ValidationIssue.warning("object", object.id(),
                        object.count() + " members is above the comfortable limit of "
                                + ObjectSpec.SOFT_MEMBER_LIMIT
                                + ". Every member is a real object with its own memories, so ticks slow down,"
                                + " checkpoints grow, and interaction rules that compare pairs get much more"
                                + " expensive."));
            }
        }
    }

    private static void checkCouplings(SystemSpec spec, Map<String, Integer> counts, List<ValidationIssue> issues) {
        for (LinkSpec link : spec.links()) {
            int source = membersBehind(link.source(), counts);
            int target = membersBehind(link.target(), counts);
            Coupling coupling = link.coupling();

            switch (coupling.mode()) {
                case ONE_TO_ONE -> {
                    if (source != target) {
                        issues.add(ValidationIssue.error("link", link.id(),
                                "pairs members one to one, but the source has " + source
                                        + " and the target has " + target
                                        + ". Use an aggregate coupling, or make the groups the same size."));
                    }
                }
                case MANY_TO_MANY_ALL -> {
                    long pairs = (long) source * target;
                    if (pairs > Coupling.MAX_PAIRS) {
                        issues.add(ValidationIssue.error("link", link.id(),
                                "connects every member to every member, which is " + source + " x " + target
                                        + " = " + pairs + " pairs each tick; the limit is " + Coupling.MAX_PAIRS
                                        + ". Reduce a group, or couple through an aggregate instead."));
                    }
                }
                case MANY_TO_ONE, MANY_TO_MANY_AGGREGATE, MANY_TO_MANY_RANDOM -> {
                    if (source <= 1) {
                        issues.add(ValidationIssue.warning("link", link.id(),
                                "reduces its source across members, but the source is a single object, so the"
                                        + " coupling has nothing to combine and behaves as a plain link."));
                    }
                }
                case ONE_TO_MANY -> {
                    if (source > 1) {
                        issues.add(ValidationIssue.warning("link", link.id(),
                                "sends one value to every target, but its source is a group of " + source
                                        + "; only the first member is read. Choose an aggregate coupling to use"
                                        + " all of them."));
                    }
                }
                case AUTO -> {
                    // Inferred at run time from whatever the endpoints turn out to be.
                }
            }
        }
    }

    /** Members behind an endpoint: a group's count, or one for anything that is not a group. */
    private static int membersBehind(VariableRef ref, Map<String, Integer> counts) {
        return ref instanceof VariableRef.OfObject ofObject ? counts.getOrDefault(ofObject.objectId(), 1) : 1;
    }
}
