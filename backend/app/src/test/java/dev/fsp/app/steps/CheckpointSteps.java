package dev.fsp.app.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.app.support.World;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Steps for checkpointing, restoring and forking. */
public class CheckpointSteps {

    @Autowired
    private World world;

    private String originalRunId;
    private String forkedRunId;

    @When("a checkpoint called {string} is taken")
    public void aCheckpointIsTaken(String label) {
        JsonNode checkpoints = world.post("/api/v1/runs/" + world.runId() + "/checkpoints",
                Map.of("label", label));
        JsonNode newest = checkpoints.get(0);
        world.checkpointId(newest.get("id").asString());
        world.rememberCheckpoint(label, newest.get("id").asString());
    }

    @When("a checkpoint is taken on the second run")
    public void aCheckpointIsTakenOnTheSecondRun() {
        JsonNode checkpoints = world.post("/api/v1/runs/" + world.secondRunId() + "/checkpoints",
                Map.of("label", "resume point"));
        world.checkpointId(checkpoints.get(0).get("id").asString());
    }

    @When("the run is restored to the checkpoint {string}")
    public void theRunIsRestored(String label) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("checkpointId", world.checkpointNamed(label));
        body.put("fork", false);
        world.post("/api/v1/runs/" + world.runId() + "/restore", body);
    }

    @When("the second run is restored from that checkpoint")
    public void theSecondRunIsRestored() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("checkpointId", world.checkpointId());
        body.put("fork", false);
        world.post("/api/v1/runs/" + world.secondRunId() + "/restore", body);
    }

    @When("the checkpoint {string} is forked into a new run")
    public void theCheckpointIsForked(String label) {
        originalRunId = world.runId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("checkpointId", world.checkpointNamed(label));
        body.put("fork", true);
        body.put("name", "forked run");
        JsonNode fork = world.post("/api/v1/runs/" + originalRunId + "/restore", body);
        forkedRunId = fork.get("id").asString();
    }

    @When("the run state digest is remembered")
    public void rememberDigest() {
        world.rememberDigest("straight", RunSteps.digestOf(world, world.runId()));
    }

    @Then("the run has {int} checkpoint")
    @Then("the run has {int} checkpoints")
    public void theRunHasCheckpoints(int count) {
        assertThat(world.get("/api/v1/runs/" + world.runId() + "/checkpoints")).hasSize(count);
    }

    @Then("the checkpoint is at tick {long}")
    public void theCheckpointIsAtTick(long tick) {
        assertThat(world.get("/api/v1/runs/" + world.runId() + "/checkpoints").get(0).get("tick").asLong())
                .isEqualTo(tick);
    }

    @Then("the forked run is at tick {long}")
    public void theForkedRunIsAtTick(long tick) {
        assertThat(world.tickOf(forkedRunId)).isEqualTo(tick);
    }

    @Then("the forked run records that it branched from the original at tick {long}")
    public void theForkRecordsItsOrigin(long tick) {
        JsonNode summary = world.runDetail(forkedRunId).get("summary");
        assertThat(summary.get("parentRunId").asString()).isEqualTo(originalRunId);
        assertThat(summary.get("forkedFromTick").asLong()).isEqualTo(tick);
    }

    @Then("the original run is still at tick {long}")
    public void theOriginalIsUntouched(long tick) {
        assertThat(world.tickOf(originalRunId)).isEqualTo(tick);
    }

    @Then("the second run state digest matches the remembered one")
    public void digestsMatch() {
        assertThat(RunSteps.digestOf(world, world.secondRunId())).isEqualTo(world.digest("straight"));
    }
}
