package dev.fsp.engine.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One thing an object remembers about another: an interaction it took part in, or something it
 * merely saw or was told.
 *
 * <p>Mutable by design. A long run holds hundreds of thousands of these and touches them every
 * tick, so the engine keeps one object per memory and mutates it, taking a deep copy only when a
 * checkpoint is written. Everything that changes is append-only history plus a cached strength,
 * which keeps the mutation easy to reason about.
 */
public final class MemoryRecord implements Trace {

    /**
     * How many individual reactivations are kept. Beyond this the running totals carry the
     * history: models that need individual timings fall back to an approximation, which costs a
     * little accuracy on very old rehearsals and buys a bounded per-memory footprint and a
     * strength calculation that does not slow down as a run gets longer.
     */
    public static final int RETAINED_REACTIVATIONS = 32;

    private final long id;
    private final String ownerId;
    private final String subjectId;
    private final String kind;
    private final long createdTick;
    private final double initialStrength;
    private final double salience;
    private final Map<String, Double> features;
    private final DecayModel decayModel;
    private final String sourceEventId;

    private double valence;
    private long originTick;
    private final List<Reactivation> reactivations = new ArrayList<>(0);
    private final List<Reactivation> reactivationsView = java.util.Collections.unmodifiableList(reactivations);
    private int similarCount;
    private int totalReactivations;
    private double totalBoost;
    private double totalStabilityGain;
    private double cachedStrength;
    private long cachedAtTick = Long.MIN_VALUE;

    /**
     * Rebuilds a memory from stored fields, history included.
     *
     * <p>Restoring has to reproduce the trace exactly, not approximately: a resumed run must
     * produce the same numbers as the run it was resumed from, and strength depends on the whole
     * reactivation history.
     */
    public static MemoryRecord restore(Builder builder, long originTick, List<Reactivation> retainedReactivations,
            int totalReactivations, double totalBoost, double totalStabilityGain, int similarCount) {
        MemoryRecord record = builder.build();
        record.originTick = originTick;
        record.reactivations.addAll(retainedReactivations);
        record.totalReactivations = Math.max(totalReactivations, retainedReactivations.size());
        record.totalBoost = totalBoost;
        record.totalStabilityGain = totalStabilityGain;
        record.similarCount = similarCount;
        return record;
    }

    private MemoryRecord(Builder builder) {
        this.id = builder.id;
        this.ownerId = builder.ownerId;
        this.subjectId = builder.subjectId;
        this.kind = builder.kind;
        this.createdTick = builder.createdTick;
        this.originTick = builder.createdTick;
        this.initialStrength = Math.clamp(builder.initialStrength, 0.0, 1.0);
        this.valence = Math.clamp(builder.valence, -1.0, 1.0);
        this.salience = Math.clamp(builder.salience, 0.0, 1.0);
        this.features = Map.copyOf(builder.features);
        this.decayModel = builder.decayModel;
        this.sourceEventId = builder.sourceEventId;
    }

    public static Builder builder(long id, String ownerId, String subjectId, long createdTick, DecayModel decayModel) {
        return new Builder(id, ownerId, subjectId, createdTick, decayModel);
    }

    public long id() {
        return id;
    }

    /** The object that holds this memory. */
    public String ownerId() {
        return ownerId;
    }

    /** The object the memory is about. May equal {@link #ownerId()} for memories about oneself. */
    public String subjectId() {
        return subjectId;
    }

    /** Free-form category: {@code interaction}, {@code observation}, {@code hearsay}, module-defined. */
    public String kind() {
        return kind;
    }

    /** How the memory feels, from -1 (wholly negative) to +1 (wholly positive). */
    public double valence() {
        return valence;
    }

    /** How attention-grabbing the event was; drives which memories cue others. */
    public double salience() {
        return salience;
    }

    /** Feature vector used for cue-similarity matching. */
    public Map<String, Double> features() {
        return features;
    }

    public DecayModel decayModel() {
        return decayModel;
    }

    /** Id of the event that laid this memory down, if any, for tracing cause to effect. */
    public String sourceEventId() {
        return sourceEventId;
    }

    @Override
    public double initialStrength() {
        return initialStrength;
    }

    @Override
    public long createdTick() {
        return createdTick;
    }

    @Override
    public long originTick() {
        return originTick;
    }

    /**
     * Read-only view rather than a copy: decay models walk this list on every strength evaluation,
     * and a run evaluates strength millions of times.
     */
    @Override
    public List<Reactivation> reactivations() {
        return reactivationsView;
    }

    @Override
    public int similarCount() {
        return similarCount;
    }

    @Override
    public int reactivationCount() {
        return totalReactivations;
    }

    @Override
    public boolean hasCompleteHistory() {
        return totalReactivations == reactivations.size();
    }

    @Override
    public double totalBoost() {
        return totalBoost;
    }

    @Override
    public double totalStabilityGain() {
        return totalStabilityGain;
    }

    public long lastReactivationTick() {
        return reactivations.isEmpty() ? Long.MIN_VALUE : reactivations.getLast().tick();
    }

    /** Current strength, memoised per tick because a tick may query the same memory many times. */
    public double strengthAt(long tick) {
        if (cachedAtTick != tick) {
            cachedStrength = decayModel.strengthAt(this, tick);
            cachedAtTick = tick;
        }
        return cachedStrength;
    }

    /** Records a retrieval and applies its consolidation, stability and mood effects. */
    public void reactivate(Reactivation reactivation) {
        reactivations.add(reactivation);
        if (reactivations.size() > RETAINED_REACTIVATIONS) {
            reactivations.removeFirst();
        }
        totalReactivations++;
        totalBoost += reactivation.boost();
        totalStabilityGain += reactivation.stabilityGain();
        if (reactivation.resetClock()) {
            originTick = reactivation.tick();
        }
        invalidate();
    }

    /** Shifts how the memory feels; repeated rumination can turn a slight into a grudge. */
    public void shiftValence(double delta) {
        valence = Math.clamp(valence + delta, -1.0, 1.0);
    }

    void setSimilarCount(int count) {
        if (similarCount != count) {
            similarCount = count;
            invalidate();
        }
    }

    private void invalidate() {
        cachedAtTick = Long.MIN_VALUE;
    }

    /**
     * Cosine similarity of feature vectors, in {@code [0, 1]} for non-negative features. Used both
     * for cue-based reactivation and for counting interfering memories.
     */
    public double similarityTo(Map<String, Double> otherFeatures) {
        if (features.isEmpty() || otherFeatures.isEmpty()) {
            return 0.0;
        }
        double dot = 0.0;
        double normSelf = 0.0;
        double normOther = 0.0;
        for (Map.Entry<String, Double> entry : features.entrySet()) {
            double self = entry.getValue();
            normSelf += self * self;
            Double other = otherFeatures.get(entry.getKey());
            if (other != null) {
                dot += self * other;
            }
        }
        for (double other : otherFeatures.values()) {
            normOther += other * other;
        }
        if (normSelf == 0.0 || normOther == 0.0) {
            return 0.0;
        }
        return Math.clamp(dot / (Math.sqrt(normSelf) * Math.sqrt(normOther)), -1.0, 1.0);
    }

    /** Deep copy, used when a checkpoint forks a run. */
    public MemoryRecord copy() {
        MemoryRecord copy = builder(id, ownerId, subjectId, createdTick, decayModel).kind(kind)
                .initialStrength(initialStrength).valence(valence).salience(salience).features(features)
                .sourceEventId(sourceEventId).build();
        copy.originTick = originTick;
        copy.reactivations.addAll(reactivations);
        copy.totalReactivations = totalReactivations;
        copy.totalBoost = totalBoost;
        copy.totalStabilityGain = totalStabilityGain;
        copy.similarCount = similarCount;
        return copy;
    }

    @Override
    public String toString() {
        return "Memory[" + id + " " + ownerId + "->" + subjectId + " " + kind + " @" + createdTick + "]";
    }

    /** Builder, because a memory has more optional attributes than a constructor call can carry legibly. */
    public static final class Builder {

        private final long id;
        private final String ownerId;
        private final String subjectId;
        private final long createdTick;
        private final DecayModel decayModel;

        private String kind = "interaction";
        private double initialStrength = 1.0;
        private double valence;
        private double salience = 0.5;
        private Map<String, Double> features = Map.of();
        private String sourceEventId;

        private Builder(long id, String ownerId, String subjectId, long createdTick, DecayModel decayModel) {
            this.id = id;
            this.ownerId = java.util.Objects.requireNonNull(ownerId, "ownerId");
            this.subjectId = java.util.Objects.requireNonNull(subjectId, "subjectId");
            this.createdTick = createdTick;
            this.decayModel = java.util.Objects.requireNonNull(decayModel, "decayModel");
        }

        public Builder kind(String kind) {
            this.kind = kind;
            return this;
        }

        public Builder initialStrength(double initialStrength) {
            this.initialStrength = initialStrength;
            return this;
        }

        public Builder valence(double valence) {
            this.valence = valence;
            return this;
        }

        public Builder salience(double salience) {
            this.salience = salience;
            return this;
        }

        public Builder features(Map<String, Double> features) {
            this.features = new LinkedHashMap<>(features);
            return this;
        }

        public Builder sourceEventId(String sourceEventId) {
            this.sourceEventId = sourceEventId;
            return this;
        }

        public MemoryRecord build() {
            return new MemoryRecord(this);
        }
    }
}
