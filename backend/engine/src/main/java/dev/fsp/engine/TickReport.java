package dev.fsp.engine;

import java.util.List;
import java.util.Map;

/**
 * What happened during one tick.
 *
 * <p>Returned rather than logged, so the host application decides what to persist, what to stream
 * to the UI, and what to discard. Everything here is what a user would need to answer "why did that
 * variable move".
 */
public record TickReport(long tick, List<EventOccurrence> events, List<ReactivationRecord> reactivations,
        List<String> logLines, int memoriesCreated, int memoriesForgotten, Map<String, Double> linkContributions) {

    public TickReport {
        events = List.copyOf(events);
        reactivations = List.copyOf(reactivations);
        logLines = List.copyOf(logLines);
        linkContributions = Map.copyOf(linkContributions);
    }

    public boolean isQuiet() {
        return events.isEmpty() && reactivations.isEmpty() && memoriesCreated == 0;
    }

    /**
     * One firing of an event.
     *
     * @param cascaded true when another event emitted this one rather than its own generator
     */
    public record EventOccurrence(String eventId, List<String> targetIds, boolean cascaded) {

        public EventOccurrence {
            targetIds = List.copyOf(targetIds);
        }
    }

    /**
     * One memory coming back.
     *
     * @param strengthBefore strength immediately before the trigger fired
     * @param strengthAfter  strength immediately after, showing what the reactivation was worth
     */
    public record ReactivationRecord(long memoryId, String ownerId, String subjectId, String triggerId,
            double strengthBefore, double strengthAfter) {

        public double gain() {
            return strengthAfter - strengthBefore;
        }
    }
}
