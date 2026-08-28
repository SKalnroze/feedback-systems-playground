package dev.fsp.app.steps;

import dev.fsp.app.support.World;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Scenario isolation.
 *
 * <p>The application context is shared by the whole suite, so each scenario starts from cleared
 * state and, more importantly, leaves nothing running behind it.
 */
public class ScenarioHooks {

    @Autowired
    private World world;

    @Before
    public void resetWorld() {
        world.reset();
    }

    @After
    public void stopRunsThisScenarioStarted() {
        world.stopCreatedRuns();
    }
}
