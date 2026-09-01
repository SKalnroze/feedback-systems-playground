package dev.fsp.modules.learning;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.engine.Engine;
import dev.fsp.engine.module.SimulationModule;
import dev.fsp.engine.state.SimulationState;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/**
 * The module is exercised through the same route the application uses, service loading included:
 * a pack that only works when wired up by hand has not proved the extension point works.
 */
class LearningModuleTest {

    private static final long SEED = 90_210L;

    @Test
    void isDiscoveredByServiceLoader() {
        List<String> ids = ServiceLoader.load(SimulationModule.class).stream()
                .map(provider -> provider.get().id()).toList();
        assertThat(ids).contains(LearningModule.ID);
    }

    @Test
    void bothPresetsRunAndStayInBounds() {
        for (SimulationModule.Preset preset : LearningPresets.all()) {
            Engine engine = new Engine(preset.spec(), new LearningModule().interactionRules());
            SimulationState state = engine.createInitialState(SEED);
            for (int tick = 0; tick < 300; tick++) {
                engine.tick(state);
            }
            var learner = state.object("sam");
            assertThat(learner.get(Learner.ATTAINMENT)).as("attainment in %s", preset.id()).isBetween(0.0, 1.0);
            assertThat(learner.get(Learner.CONFIDENCE)).as("confidence in %s", preset.id()).isBetween(0.0, 1.0);
            assertThat(state.memories().size()).as("memories laid down in %s", preset.id()).isPositive();
        }
    }

    /**
     * The claim the pair of presets exists to make: cramming leaves the learner believing more than
     * they can show, and spaced practice does not.
     */
    @Test
    void crammingOverstatesWhatWasLearned() {
        double spacedGap = beliefGapAfter(LearningPresets.spacedPractice(), 300);
        double crammedGap = beliefGapAfter(LearningPresets.examCram(), 300);
        assertThat(crammedGap).as("confidence minus attainment").isGreaterThan(spacedGap);
    }

    private static double beliefGapAfter(SimulationModule.Preset preset, int ticks) {
        Engine engine = new Engine(preset.spec(), new LearningModule().interactionRules());
        SimulationState state = engine.createInitialState(SEED);
        for (int tick = 0; tick < ticks; tick++) {
            engine.tick(state);
        }
        var learner = state.object("sam");
        return learner.get(Learner.CONFIDENCE) - learner.get(Learner.ATTAINMENT);
    }
}
