package dev.fsp.engine.graph;

import dev.fsp.engine.spec.LinkSpec;
import java.util.List;

/**
 * A cycle found in the link graph, with its polarity worked out.
 *
 * <p>Surfacing these is most of what makes a system legible: a diagram of twenty arrows tells you
 * very little, whereas "there are two reinforcing loops and one balancing loop, and the balancing
 * one is delayed by eight ticks" tells you what the run will look like before you start it.
 *
 * @param linkIds     the links forming the cycle, in order
 * @param polarity    reinforcing or balancing
 * @param totalDelay  sum of the delays around the loop; long delays are what produce oscillation
 */
public record FeedbackLoop(List<String> linkIds, Polarity polarity, int totalDelay) {

    public enum Polarity {

        /** An even number of negative links: deviation feeds on itself and grows. */
        REINFORCING,

        /** An odd number of negative links: deviation is opposed and the loop seeks equilibrium. */
        BALANCING
    }

    public FeedbackLoop {
        linkIds = List.copyOf(linkIds);
    }

    public int length() {
        return linkIds.size();
    }

    /** Loops that both oppose change and take a long time to do it are the ones that oscillate. */
    public boolean isOscillationRisk() {
        return polarity == Polarity.BALANCING && totalDelay >= 2;
    }

    /** Works out polarity and delay for a cycle of links. */
    public static FeedbackLoop of(List<LinkSpec> cycle) {
        int negatives = 0;
        int delay = 0;
        List<String> ids = new java.util.ArrayList<>(cycle.size());
        for (LinkSpec link : cycle) {
            if (link.gain() < 0.0) {
                negatives++;
            }
            delay += link.delayTicks();
            ids.add(link.id());
        }
        Polarity polarity = negatives % 2 == 0 ? Polarity.REINFORCING : Polarity.BALANCING;
        return new FeedbackLoop(ids, polarity, delay);
    }
}
