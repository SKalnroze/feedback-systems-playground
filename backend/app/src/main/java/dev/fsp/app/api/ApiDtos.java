package dev.fsp.app.api;

import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.spec.ObjectTypeSpec;
import dev.fsp.engine.spec.SystemSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;

/** Request and response shapes for the HTTP API. */
public final class ApiDtos {

    private ApiDtos() {
    }

    // --- systems -------------------------------------------------------------------------------

    public record CreateSystemRequest(@NotBlank String name, String description, SystemSpec draft) {
    }

    public record SaveDraftRequest(String name, String description, SystemSpec draft) {
    }

    public record CreateFromPresetRequest(@NotBlank String presetId, String name) {
    }

    public record ValidateRequest(SystemSpec spec) {
    }

    // --- runs ----------------------------------------------------------------------------------

    /**
     * @param seed      null asks for a random seed, which is then recorded so the run stays repeatable;
     *                  sent as a string for the same precision reason as {@link RunSummaryView#seed()}
     * @param speed     ticks per second; null means the default, zero or less means unthrottled
     * @param autoStart start immediately rather than waiting for a play command
     */
    public record CreateRunRequest(String systemVersionId, String name, String description, String seed, Double speed,
            boolean autoStart) {

        /** @throws IllegalArgumentException if the seed was supplied but is not a 64-bit integer */
        public Long parsedSeed() {
            if (seed == null || seed.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(seed.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("seed must be a whole number: " + seed);
            }
        }
    }

    /** @param ticks number of ticks for the STEP action; ignored otherwise */
    public record ControlRequest(@NotBlank String action, Integer ticks) {
    }

    public record SpeedRequest(double ticksPerSecond) {
    }

    public record CheckpointRequest(String label) {
    }

    /** @param fork true creates a separate run branching at the checkpoint, leaving this one alone */
    public record RestoreRequest(@NotBlank String checkpointId, boolean fork, String name) {
    }

    public record RenameRequest(@NotBlank String name) {
    }

    // --- modules -------------------------------------------------------------------------------

    public record ModuleView(String id, String label, String description, List<ObjectTypeSpec> objectTypes,
            List<RuleView> rules, List<PresetView> presets) {
    }

    public record RuleView(String id, String label, String description, List<InteractionRule.Parameter> parameters) {
    }

    public record PresetView(String id, String label, String description, String moduleId) {
    }

    /**
     * The editor palette: every discriminator the spec format accepts, grouped by hierarchy.
     * Generated from the same table the JSON mapper uses, so the two cannot drift apart.
     */
    public record PaletteView(Map<String, List<String>> kinds, List<ObjectTypeSpec> objectTypes,
            List<RuleView> rules) {
    }

    // --- observability -------------------------------------------------------------------------

    /** @param resolution ticks per point actually used, which may be coarser than requested */
    public record SeriesResponse(String runId, long fromTick, long toTick, int resolution,
            List<SeriesView> series) {
    }

    public record SeriesView(String key, List<Number[]> points, boolean bucketed) {
    }

    public record RelationshipView(String ownerId, String subjectId, double meanValence, double totalStrength,
            int memoryCount) {
    }

    /** Editing what something was for. Blank is allowed: clearing a description is a real intent. */
    /**
     * @param seed the run's master seed, as a string
     *
     * <p>A seed is a 64-bit value and JavaScript numbers lose precision beyond 2^53, so sending it
     * as a JSON number means the browser shows a seed that differs from the one the run actually
     * used - and reproducing a run from the displayed value would produce a different simulation.
     * As a string it survives the trip intact.
     */
    public record DescribeRequest(String description) {
    }

    public record RunSummaryView(String id, String name, String description, String status, long tick, double speed,
            String seed,
            String systemName, String parentRunId, Long forkedFromTick, int watchers, String error) {
    }

    /** @param ticksPerSecondActual measured pace, which is what tells a user a run is falling behind */
    public record RunDetailView(RunSummaryView summary, SystemSpec spec, long sampleCount, long logEntryCount,
            long ticksPerSecondActual, Map<String, Double> latestValues) {
    }

    public record CheckpointView(String id, long tick, String label, String description, int stateBytes,
            boolean automatic,
            String createdAt) {
    }

    public record LogEntryView(long id, long tick, String type, String subject, String detail) {
    }

    public record MemoryView(long id, String ownerId, String subjectId, String kind, long createdTick,
            double initialStrength, double currentStrength, double valence, int reactivationCount) {
    }

    /** Page of results, so a run with a million log lines does not have to be sent at once. */
    public record Page<T>(List<T> items, int limit, boolean hasMore) {

        public static <T> Page<T> of(List<T> items, @Positive int limit) {
            boolean hasMore = items.size() > limit;
            return new Page<>(hasMore ? items.subList(0, limit) : items, limit, hasMore);
        }
    }
}
