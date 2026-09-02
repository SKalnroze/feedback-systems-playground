package dev.fsp.app.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fsp.app.support.World;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Steps that create runs and drive them. */
public class RunSteps {

    @Autowired
    private World world;

    @Autowired
    private dev.fsp.app.run.RunEngineHost host;

    @Autowired
    private dev.fsp.app.run.RunService runs;

    @Autowired
    private dev.fsp.app.persistence.MetricRepository metrics;

    @Autowired
    private dev.fsp.app.persistence.RunRepository checkpoints;

    private long pausedAtTick;

    @Given("a run is created")
    public void aRunIsCreated() {
        world.createRun(4242L);
    }

    @Given("a run is created with seed {long}")
    public void aRunIsCreatedWithSeed(long seed) {
        world.createRun(seed);
    }

    @Given("a second run is created with seed {long}")
    public void aSecondRunIsCreated(long seed) {
        String first = world.runId();
        world.useRun(null);
        world.secondRunId(world.createRun(seed));
        world.useRun(first);
    }

    @When("the run advances to tick {long}")
    public void theRunAdvancesToTick(long target) {
        String runId = world.runId();
        long current = world.tickOf(runId);
        if (current < target) {
            world.step(runId, (int) (target - current));
        }
    }

    /** Ages are counted from when the memory formed, which is the only thing decay depends on. */
    @When("the memory is {int} ticks old")
    public void theMemoryIsTicksOld(int age) {
        theRunAdvancesToTick(World.MEMORY_SEED_TICK + age);
    }

    @When("the run is stepped by {int} ticks")
    public void theRunIsSteppedBy(int ticks) {
        world.step(world.runId(), ticks);
    }

    @When("the second run is stepped by {int} ticks")
    public void theSecondRunIsSteppedBy(int ticks) {
        world.step(world.secondRunId(), ticks);
    }

    @When("the run is started at {int} ticks per second")
    public void theRunIsStarted(int ticksPerSecond) {
        world.patch("/api/v1/runs/" + world.runId() + "/speed", Map.of("ticksPerSecond", ticksPerSecond));
        world.post("/api/v1/runs/" + world.runId() + "/control", Map.of("action", "START"));
    }

    @When("the run reaches at least tick {long}")
    public void theRunReachesTick(long target) {
        world.awaitTick(world.runId(), target);
    }

    @When("the run is paused")
    public void theRunIsPaused() {
        world.post("/api/v1/runs/" + world.runId() + "/control", Map.of("action", "PAUSE"));
        world.awaitStatus(world.runId(), "PAUSED");
        pausedAtTick = world.tickOf(world.runId());
    }

    @When("the run is stopped")
    public void theRunIsStopped() {
        world.post("/api/v1/runs/" + world.runId() + "/control", Map.of("action", "STOP"));
        world.awaitStatus(world.runId(), "STOPPED");
    }

    @When("nothing happens for a moment")
    public void nothingHappens() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Then("the run status is {string}")
    public void theRunStatusIs(String status) {
        world.awaitStatus(world.runId(), status);
        assertThat(world.statusOf(world.runId())).isEqualTo(status);
    }

    @Then("the run is at tick {long}")
    public void theRunIsAtTick(long tick) {
        assertThat(world.tickOf(world.runId())).isEqualTo(tick);
    }

    @Then("the run stays at the tick it was paused at")
    public void theRunStaysPut() {
        nothingHappens();
        assertThat(world.tickOf(world.runId())).isEqualTo(pausedAtTick);
    }

    /** Drops the run from memory the way a restart or an eviction would. */
    @When("the run is unloaded from memory")
    public void theRunIsUnloadedFromMemory() {
        host.evict(java.util.UUID.fromString(world.runId()));
    }

    @When("the run is opened again")
    public void theRunIsOpenedAgain() {
        runs.ensureHosted(java.util.UUID.fromString(world.runId()));
    }

    /** Drops the run the way a killed process would: no chance to write a resume checkpoint. */
    @When("the run is unloaded from memory without checkpointing")
    public void theRunIsUnloadedWithoutCheckpointing() {
        java.util.UUID id = java.util.UUID.fromString(world.runId());
        host.evict(id);
        // Remove whatever the graceful path wrote, leaving the state a hard stop would leave.
        checkpoints.deleteAllCheckpoints(id);
    }

    /** Refusing is the point: the alternative was silently restarting at zero and deleting the run. */
    @Then("opening the run again is refused")
    public void openingTheRunAgainIsRefused() {
        assertThatThrownBy(() -> runs.ensureHosted(java.util.UUID.fromString(world.runId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no checkpoint");
    }

    @Then("samples are still recorded up to tick {long}")
    public void samplesAreStillRecordedUpTo(long tick) {
        assertThat(metrics.latestSampledTick(java.util.UUID.fromString(world.runId())))
                .as("recorded history is kept even when the run cannot be resumed")
                .isGreaterThanOrEqualTo(tick);
    }

    @Then("no samples are recorded after tick {long}")
    public void noSamplesAreRecordedAfterTick(long tick) {
        assertThat(metrics.latestSampledTick(java.util.UUID.fromString(world.runId())))
                .as("latest sampled tick")
                .isLessThanOrEqualTo(tick);
    }

    @Then("the run reports seed {long}")
    public void theRunReportsSeed(long seed) {
        // Compared as text: a seed is a 64-bit value, and reading it as a JSON number is precisely
        // the mistake this asserts against.
        assertThat(world.runDetail(world.runId()).get("summary").get("seed").asString())
                .isEqualTo(Long.toString(seed));
    }

    @Then("{string} has trust of about {double}")
    public void personHasTrust(String person, double expected) {
        JsonNode values = world.runDetail(world.runId()).get("latestValues");
        assertThat(values.get(person + ".trust").asDouble()).isCloseTo(expected, Offset.offset(0.02));
    }

    /**
     * A cheap stand-in for a state digest over the API: the full set of latest sampled values.
     * Two runs that agree on every variable at the same tick have, for every purpose this tool
     * has, produced the same run.
     */
    static String digestOf(World world, String runId) {
        JsonNode values = world.runDetail(runId).get("latestValues");
        return values == null ? "" : values.toString();
    }

    private String digestOf(String runId) {
        return digestOf(world, runId);
    }
}
