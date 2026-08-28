package dev.fsp.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.engine.expr.EvalContext.MemoryAggregateQuery;
import dev.fsp.engine.expr.EvalContext.MemoryAggregateQuery.Aggregate;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MemoryStoreTest {

    private static final Offset<Double> PRECISE = Offset.offset(1e-9);
    private static final DecayModel SLOW = DecayModel.Exponential.ofHalfLife(100.0);

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
    }

    private MemoryRecord remember(String owner, String subject, String kind, long tick, double strength) {
        return store.add(MemoryRecord.builder(store.nextId(), owner, subject, tick, SLOW).kind(kind)
                .initialStrength(strength).build());
    }

    @Nested
    @DisplayName("storage and lookup")
    class Storage {

        @Test
        void groupsMemoriesByOwner() {
            remember("alice", "bob", "interaction", 0L, 0.9);
            remember("alice", "carol", "observation", 1L, 0.4);
            remember("bob", "alice", "interaction", 1L, 0.7);

            assertThat(store.of("alice")).hasSize(2);
            assertThat(store.of("bob")).hasSize(1);
            assertThat(store.of("nobody")).isEmpty();
            assertThat(store.owners()).containsExactly("alice", "bob");
            assertThat(store.size()).isEqualTo(3);
        }

        @Test
        void findsMemoriesAboutOneSubject() {
            remember("alice", "bob", "interaction", 0L, 0.9);
            remember("alice", "bob", "observation", 2L, 0.3);
            remember("alice", "carol", "interaction", 3L, 0.5);

            assertThat(store.about("alice", "bob")).hasSize(2);
            assertThat(store.about("alice", "carol")).hasSize(1);
        }

        @Test
        void issuesSequentialIds() {
            assertThat(List.of(store.nextId(), store.nextId(), store.nextId())).containsExactly(1L, 2L, 3L);
        }

        @Test
        void visitsEveryMemoryInInsertionOrder() {
            remember("alice", "bob", "interaction", 0L, 0.9);
            remember("bob", "alice", "interaction", 1L, 0.8);

            java.util.List<Long> visited = new java.util.ArrayList<>();
            store.forEach(record -> visited.add(record.id()));

            assertThat(visited).containsExactly(1L, 2L);
        }

        @Test
        void dropsEverythingInvolvingADepartedObject() {
            remember("alice", "bob", "interaction", 0L, 0.9);
            remember("carol", "bob", "observation", 0L, 0.5);
            remember("carol", "alice", "observation", 0L, 0.5);

            store.removeAllInvolving("bob");

            assertThat(store.of("bob")).isEmpty();
            assertThat(store.of("alice")).isEmpty();
            assertThat(store.of("carol")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("cue matching")
    class CueMatching {

        @Test
        void ranksMemoriesByHowWellTheyMatchTheCue() {
            store.add(MemoryRecord.builder(1L, "alice", "bob", 0L, SLOW)
                    .features(Map.of("conflict", 1.0, "public", 1.0)).build());
            store.add(MemoryRecord.builder(2L, "alice", "bob", 0L, SLOW).features(Map.of("conflict", 1.0)).build());
            store.add(MemoryRecord.builder(3L, "alice", "bob", 0L, SLOW).features(Map.of("holiday", 1.0)).build());

            List<MemoryRecord> matches = store.matchingCue("alice", Map.of("conflict", 1.0), 0.5);

            assertThat(matches).extracting(MemoryRecord::id).containsExactly(2L, 1L);
        }

        @Test
        void ignoresMemoriesWithoutFeatures() {
            remember("alice", "bob", "interaction", 0L, 0.9);

            assertThat(store.matchingCue("alice", Map.of("conflict", 1.0), 0.1)).isEmpty();
        }

        @Test
        void treatsAnEmptyCueAsMatchingNothing() {
            store.add(MemoryRecord.builder(1L, "alice", "bob", 0L, SLOW).features(Map.of("conflict", 1.0)).build());

            assertThat(store.matchingCue("alice", Map.of(), 0.0)).isEmpty();
        }
    }

    @Nested
    @DisplayName("interference")
    class Interference {

        @Test
        void countsSimilarMemoriesAboutTheSameSubject() {
            store.add(MemoryRecord.builder(1L, "alice", "bob", 0L, SLOW).features(Map.of("lunch", 1.0)).build());
            store.add(MemoryRecord.builder(2L, "alice", "bob", 1L, SLOW).features(Map.of("lunch", 1.0)).build());
            store.add(MemoryRecord.builder(3L, "alice", "bob", 2L, SLOW).features(Map.of("argument", 1.0)).build());

            store.refreshInterference("alice", 0.6);

            assertThat(store.of("alice")).extracting(MemoryRecord::similarCount).containsExactly(1, 1, 0);
        }

        @Test
        void doesNotCountSimilarMemoriesAboutDifferentSubjects() {
            store.add(MemoryRecord.builder(1L, "alice", "bob", 0L, SLOW).features(Map.of("lunch", 1.0)).build());
            store.add(MemoryRecord.builder(2L, "alice", "carol", 1L, SLOW).features(Map.of("lunch", 1.0)).build());

            store.refreshInterference("alice", 0.6);

            assertThat(store.of("alice")).extracting(MemoryRecord::similarCount).containsExactly(0, 0);
        }
    }

    @Nested
    @DisplayName("pruning")
    class Pruning {

        @Test
        void dropsMemoriesBelowTheRetrievalThresholdWhenAsked() {
            MemorySettings settings = new MemorySettings(DecayModel.Exponential.ofHalfLife(10.0), 0.1, 0, true, 0.6);
            remember("alice", "bob", "interaction", 0L, 1.0);
            remember("alice", "bob", "interaction", 900L, 1.0);

            int removed = store.prune("alice", 1_000L, settings);

            assertThat(removed).isEqualTo(1);
            assertThat(store.of("alice")).hasSize(1);
            assertThat(store.forgottenCount()).isEqualTo(1L);
        }

        @Test
        void keepsWeakMemoriesWhenPruningIsDisabled() {
            MemorySettings settings = new MemorySettings(DecayModel.Exponential.ofHalfLife(10.0), 0.1, 0, false, 0.6);
            remember("alice", "bob", "interaction", 0L, 1.0);

            assertThat(store.prune("alice", 1_000L, settings)).isZero();
            assertThat(store.of("alice")).hasSize(1);
        }

        @Test
        void enforcesCapacityByDroppingTheWeakestFirst() {
            MemorySettings settings = new MemorySettings(SLOW, 0.0, 2, false, 0.6);
            remember("alice", "bob", "interaction", 0L, 0.2);
            remember("alice", "bob", "interaction", 0L, 0.9);
            remember("alice", "bob", "interaction", 0L, 0.5);

            int removed = store.prune("alice", 0L, settings);

            assertThat(removed).isEqualTo(1);
            assertThat(store.of("alice")).extracting(MemoryRecord::initialStrength).containsExactly(0.9, 0.5);
        }

        @Test
        void handlesOwnersWithNoMemories() {
            assertThat(store.prune("ghost", 0L, MemorySettings.DEFAULT)).isZero();
        }
    }

    @Nested
    @DisplayName("aggregates")
    class Aggregates {

        @BeforeEach
        void populate() {
            store.add(MemoryRecord.builder(store.nextId(), "alice", "bob", 0L, DecayModel.NoDecay.INSTANCE)
                    .kind("interaction").initialStrength(0.8).valence(-0.5).build());
            store.add(MemoryRecord.builder(store.nextId(), "alice", "bob", 0L, DecayModel.NoDecay.INSTANCE)
                    .kind("observation").initialStrength(0.4).valence(1.0).build());
            store.add(MemoryRecord.builder(store.nextId(), "alice", "carol", 0L, DecayModel.NoDecay.INSTANCE)
                    .kind("interaction").initialStrength(0.6).valence(0.2).build());
        }

        @Test
        void countsAndSumsAcrossAllSubjects() {
            assertThat(aggregate(Aggregate.COUNT, null, null, 0.0)).isCloseTo(3.0, PRECISE);
            assertThat(aggregate(Aggregate.SUM_STRENGTH, null, null, 0.0)).isCloseTo(1.8, PRECISE);
            assertThat(aggregate(Aggregate.MEAN_STRENGTH, null, null, 0.0)).isCloseTo(0.6, PRECISE);
            assertThat(aggregate(Aggregate.MAX_STRENGTH, null, null, 0.0)).isCloseTo(0.8, PRECISE);
        }

        @Test
        void restrictsToOneSubject() {
            assertThat(aggregate(Aggregate.COUNT, "bob", null, 0.0)).isCloseTo(2.0, PRECISE);
            assertThat(aggregate(Aggregate.SUM_STRENGTH, "bob", null, 0.0)).isCloseTo(1.2, PRECISE);
        }

        @Test
        void restrictsToOneKind() {
            assertThat(aggregate(Aggregate.COUNT, null, "observation", 0.0)).isCloseTo(1.0, PRECISE);
        }

        @Test
        void ignoresMemoriesBelowTheMinimumStrength() {
            assertThat(aggregate(Aggregate.COUNT, null, null, 0.5)).isCloseTo(2.0, PRECISE);
        }

        @Test
        void weightsValenceByStrengthSoFadedMemoriesMatterLess() {
            assertThat(aggregate(Aggregate.SUM_VALENCE, "bob", null, 0.0)).isCloseTo(-0.5 * 0.8 + 1.0 * 0.4, PRECISE);
            assertThat(aggregate(Aggregate.MEAN_VALENCE, "bob", null, 0.0)).isCloseTo(0.0, PRECISE);
        }

        @Test
        void returnsZeroWhenNothingMatches() {
            assertThat(aggregate(Aggregate.MEAN_STRENGTH, "nobody", null, 0.0)).isZero();
            assertThat(aggregate(Aggregate.MEAN_VALENCE, "nobody", null, 0.0)).isZero();
            assertThat(aggregate(Aggregate.MAX_STRENGTH, "nobody", null, 0.0)).isZero();
        }

        private double aggregate(Aggregate aggregate, String subject, String kind, double minStrength) {
            return store.aggregate("alice", subject, 0L,
                    new MemoryAggregateQuery(aggregate, kind, subject != null, minStrength));
        }
    }

    @Nested
    @DisplayName("copying")
    class Copying {

        @Test
        void deepCopiesEveryRecord() {
            MemoryRecord original = remember("alice", "bob", "interaction", 0L, 0.8);
            original.reactivate(new Reactivation(5L, 0.1, 1.0, true, "t1"));

            MemoryStore copy = store.copy();
            MemoryRecord copied = copy.of("alice").getFirst();
            original.shiftValence(0.5);

            assertThat(copied).isNotSameAs(original);
            assertThat(copied.id()).isEqualTo(original.id());
            assertThat(copied.originTick()).isEqualTo(5L);
            assertThat(copied.reactivationCount()).isEqualTo(1);
            assertThat(copied.valence()).isZero();
            assertThat(copy.nextId()).isEqualTo(store.nextId());
        }

        @Test
        void copiesAreIndependentForLaterAdditions() {
            remember("alice", "bob", "interaction", 0L, 0.8);
            MemoryStore copy = store.copy();

            remember("alice", "bob", "interaction", 1L, 0.5);

            assertThat(copy.of("alice")).hasSize(1);
            assertThat(store.of("alice")).hasSize(2);
        }
    }
}
