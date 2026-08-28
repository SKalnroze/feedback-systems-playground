package dev.fsp.engine.graph;

import dev.fsp.engine.spec.LinkSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the feedback loops in a link graph.
 *
 * <p>Uses Johnson's approach of enumerating elementary cycles by depth-first search from each
 * starting node, restricted to nodes not already used as a start. That keeps each cycle reported
 * once rather than once per rotation, which matters because the editor draws one badge per loop.
 *
 * <p>Cycle enumeration is exponential in the worst case, so a cap keeps a pathological graph from
 * hanging the publish request.
 */
public final class LoopDetector {

    /** Beyond this many loops the list stops being useful to a reader anyway. */
    public static final int DEFAULT_MAX_LOOPS = 200;

    private final Map<String, List<LinkSpec>> outgoing = new LinkedHashMap<>();
    private final int maxLoops;

    public LoopDetector(List<LinkSpec> links) {
        this(links, DEFAULT_MAX_LOOPS);
    }

    public LoopDetector(List<LinkSpec> links, int maxLoops) {
        this.maxLoops = maxLoops;
        for (LinkSpec link : links) {
            outgoing.computeIfAbsent(link.source().seriesKey(), key -> new ArrayList<>()).add(link);
        }
    }

    /** Every elementary cycle, each reported once, ordered by the node it starts from. */
    public List<FeedbackLoop> detect() {
        List<FeedbackLoop> loops = new ArrayList<>();
        Set<String> exhausted = new LinkedHashSet<>();

        for (String start : new ArrayList<>(outgoing.keySet())) {
            Deque<LinkSpec> path = new ArrayDeque<>();
            Set<String> onPath = new HashSet<>();
            search(start, start, path, onPath, exhausted, loops);
            if (loops.size() >= maxLoops) {
                break;
            }
            // Cycles through this node are all found; excluding it prevents duplicate rotations.
            exhausted.add(start);
        }
        return List.copyOf(loops);
    }

    private void search(String start, String current, Deque<LinkSpec> path, Set<String> onPath, Set<String> exhausted,
            List<FeedbackLoop> loops) {
        if (loops.size() >= maxLoops) {
            return;
        }
        onPath.add(current);
        for (LinkSpec link : outgoing.getOrDefault(current, List.of())) {
            String next = link.target().seriesKey();
            if (exhausted.contains(next)) {
                continue;
            }
            path.addLast(link);
            if (next.equals(start)) {
                loops.add(FeedbackLoop.of(new ArrayList<>(path)));
            } else if (!onPath.contains(next)) {
                search(start, next, path, onPath, exhausted, loops);
            }
            path.removeLast();
            if (loops.size() >= maxLoops) {
                break;
            }
        }
        onPath.remove(current);
    }

    /** Convenience for the common "does this graph have any feedback at all" question. */
    public boolean hasLoops() {
        return !detect().isEmpty();
    }
}
