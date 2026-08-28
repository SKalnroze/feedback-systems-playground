package dev.fsp.engine.memory;

import dev.fsp.engine.expr.EvalContext.MemoryAggregateQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Every memory in the simulation, indexed by who holds it.
 *
 * <p>Iteration order is insertion order throughout, because a run has to replay identically and
 * hash order would not survive a restart.
 */
public final class MemoryStore {

    private final Map<String, List<MemoryRecord>> byOwner = new LinkedHashMap<>();
    private long nextId = 1L;
    private long forgottenCount;

    /** Adds a memory and returns it. */
    public MemoryRecord add(MemoryRecord record) {
        byOwner.computeIfAbsent(record.ownerId(), key -> new ArrayList<>()).add(record);
        return record;
    }

    /**
     * Adds a memory and updates interference counts as it goes.
     *
     * <p>Incremental on purpose. Recomputing every owner's interference from scratch each tick is
     * quadratic in how much they remember, which is fine for a hundred memories and ruinous for ten
     * thousand; touching only the memories the new one actually resembles keeps it linear.
     */
    public MemoryRecord addTracked(MemoryRecord record, double similarityThreshold) {
        List<MemoryRecord> records = byOwner.computeIfAbsent(record.ownerId(), key -> new ArrayList<>());
        int similar = 0;
        for (MemoryRecord other : records) {
            if (interferes(other, record, similarityThreshold)) {
                similar++;
                other.setSimilarCount(other.similarCount() + 1);
            }
        }
        record.setSimilarCount(similar);
        records.add(record);
        return record;
    }

    /** Two memories interfere when they are about the same person and describe a similar thing. */
    private static boolean interferes(MemoryRecord first, MemoryRecord second, double similarityThreshold) {
        return first != second && first.subjectId().equals(second.subjectId())
                && first.similarityTo(second.features()) >= similarityThreshold;
    }

    /** Allocates the next memory id; ids are sequential so that ordering is reproducible. */
    public long nextId() {
        return nextId++;
    }

    /** Peeks at the next id without consuming it, for checkpointing. */
    public long peekNextId() {
        return nextId;
    }

    /** Restores the id counter when a checkpoint is loaded, so ids stay unique across a resume. */
    public void restoreNextId(long next) {
        nextId = next;
    }

    /** Restores the running count of dropped memories when a checkpoint is loaded. */
    public void restoreForgottenCount(long count) {
        forgottenCount = count;
    }

    public List<MemoryRecord> of(String ownerId) {
        return byOwner.getOrDefault(ownerId, List.of());
    }

    /** Memories held by {@code ownerId} about {@code subjectId}. */
    public List<MemoryRecord> about(String ownerId, String subjectId) {
        List<MemoryRecord> matches = new ArrayList<>();
        for (MemoryRecord record : of(ownerId)) {
            if (record.subjectId().equals(subjectId)) {
                matches.add(record);
            }
        }
        return matches;
    }

    public List<String> owners() {
        return List.copyOf(byOwner.keySet());
    }

    public int size() {
        int total = 0;
        for (List<MemoryRecord> records : byOwner.values()) {
            total += records.size();
        }
        return total;
    }

    /** Number of memories dropped so far, either forgotten or squeezed out by capacity. */
    public long forgottenCount() {
        return forgottenCount;
    }

    public void forEach(java.util.function.Consumer<MemoryRecord> action) {
        for (List<MemoryRecord> records : byOwner.values()) {
            for (MemoryRecord record : records) {
                action.accept(record);
            }
        }
    }

    /**
     * Memories of {@code ownerId} whose feature vector matches a cue closely enough. Ordered by
     * descending similarity so the strongest match is first.
     */
    public List<MemoryRecord> matchingCue(String ownerId, Map<String, Double> cue, double threshold) {
        if (cue.isEmpty()) {
            // Nothing happened that could remind anyone of anything.
            return List.of();
        }
        List<MemoryRecord> matches = new ArrayList<>();
        for (MemoryRecord record : of(ownerId)) {
            if (record.similarityTo(cue) >= threshold) {
                matches.add(record);
            }
        }
        matches.sort(Comparator.comparingDouble((MemoryRecord record) -> record.similarityTo(cue)).reversed()
                .thenComparingLong(MemoryRecord::id));
        return matches;
    }

    /**
     * Recomputes, for every memory of this owner, how many of their other memories are similar
     * enough to interfere with it. Called after memories are added or removed rather than every
     * tick, since it is quadratic in the owner's memory count.
     */
    public void refreshInterference(String ownerId, double similarityThreshold) {
        List<MemoryRecord> records = of(ownerId);
        for (MemoryRecord record : records) {
            int similar = 0;
            for (MemoryRecord other : records) {
                if (interferes(other, record, similarityThreshold)) {
                    similar++;
                }
            }
            record.setSimilarCount(similar);
        }
    }

    /**
     * Drops memories that have decayed below the retrieval threshold, then enforces the capacity
     * limit by discarding the weakest. Returns the number removed.
     */
    public int prune(String ownerId, long tick, MemorySettings settings) {
        List<MemoryRecord> records = byOwner.get(ownerId);
        if (records == null || records.isEmpty()) {
            return 0;
        }
        int before = records.size();
        List<MemoryRecord> removed = settings.usesInterference() ? new ArrayList<>() : null;
        if (settings.pruneForgotten()) {
            records.removeIf(record -> {
                boolean forgotten = record.strengthAt(tick) < settings.retrievalThreshold();
                if (forgotten && removed != null) {
                    removed.add(record);
                }
                return forgotten;
            });
        }
        if (settings.hasCapacityLimit() && records.size() > settings.capacity()) {
            List<MemoryRecord> weakestFirst = new ArrayList<>(records);
            weakestFirst.sort(Comparator.comparingDouble((MemoryRecord record) -> record.strengthAt(tick))
                    .thenComparingLong(MemoryRecord::id));
            int excess = records.size() - settings.capacity();
            List<MemoryRecord> doomed = weakestFirst.subList(0, excess);
            if (removed != null) {
                removed.addAll(doomed);
            }
            records.removeAll(doomed);
        }
        if (removed != null && !removed.isEmpty()) {
            releaseInterference(records, removed, settings.similarityThreshold());
        }
        int removedCount = before - records.size();
        forgottenCount += removedCount;
        return removedCount;
    }

    /** A forgotten memory stops crowding out the ones that remain. */
    private static void releaseInterference(List<MemoryRecord> remaining, List<MemoryRecord> removed,
            double similarityThreshold) {
        for (MemoryRecord gone : removed) {
            for (MemoryRecord survivor : remaining) {
                if (interferes(survivor, gone, similarityThreshold)) {
                    survivor.setSimilarCount(Math.max(0, survivor.similarCount() - 1));
                }
            }
        }
    }

    /** Removes every memory held by or about an object that has left the simulation. */
    public void removeAllInvolving(String objectId) {
        byOwner.remove(objectId);
        for (List<MemoryRecord> records : byOwner.values()) {
            records.removeIf(record -> record.subjectId().equals(objectId));
        }
    }

    /**
     * Evaluates an aggregate over an owner's memories.
     *
     * @param subjectId when non-null, restricts the aggregate to memories about that object
     */
    public double aggregate(String ownerId, String subjectId, long tick, MemoryAggregateQuery query) {
        Predicate<MemoryRecord> filter = record -> {
            if (subjectId != null && !record.subjectId().equals(subjectId)) {
                return false;
            }
            if (query.kind() != null && !query.kind().equals(record.kind())) {
                return false;
            }
            return record.strengthAt(tick) >= query.minStrength();
        };

        int count = 0;
        double sumStrength = 0.0;
        double maxStrength = 0.0;
        double sumValence = 0.0;
        for (MemoryRecord record : of(ownerId)) {
            if (!filter.test(record)) {
                continue;
            }
            double strength = record.strengthAt(tick);
            count++;
            sumStrength += strength;
            maxStrength = Math.max(maxStrength, strength);
            // Valence is weighted by strength: a faded memory colours judgement less than a vivid one.
            sumValence += record.valence() * strength;
        }

        return switch (query.aggregate()) {
            case COUNT -> count;
            case SUM_STRENGTH -> sumStrength;
            case MEAN_STRENGTH -> count == 0 ? 0.0 : sumStrength / count;
            case MAX_STRENGTH -> maxStrength;
            case SUM_VALENCE -> sumValence;
            case MEAN_VALENCE -> count == 0 ? 0.0 : sumValence / count;
        };
    }

    /** Deep copy for checkpointing. */
    public MemoryStore copy() {
        MemoryStore copy = new MemoryStore();
        for (Map.Entry<String, List<MemoryRecord>> entry : byOwner.entrySet()) {
            List<MemoryRecord> records = new ArrayList<>(entry.getValue().size());
            for (MemoryRecord record : entry.getValue()) {
                records.add(record.copy());
            }
            copy.byOwner.put(entry.getKey(), records);
        }
        copy.nextId = nextId;
        copy.forgottenCount = forgottenCount;
        return copy;
    }
}
