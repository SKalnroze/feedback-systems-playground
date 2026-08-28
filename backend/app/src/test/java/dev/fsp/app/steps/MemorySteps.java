package dev.fsp.app.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.app.support.World;
import io.cucumber.java.en.Then;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.data.Offset;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Assertions about what people remember and how strongly. */
public class MemorySteps {

    @Autowired
    private World world;

    @Then("the memory strength is about {double}")
    public void memoryStrengthIsAbout(double expected) {
        assertThat(soleMemory().get("currentStrength").asDouble()).isCloseTo(expected, Offset.offset(0.02));
    }

    @Then("the memory strength is above {double}")
    public void memoryStrengthIsAbove(double threshold) {
        assertThat(soleMemory().get("currentStrength").asDouble()).isGreaterThan(threshold);
    }

    @Then("the memory strength is below {double}")
    public void memoryStrengthIsBelow(double threshold) {
        assertThat(soleMemory().get("currentStrength").asDouble()).isLessThan(threshold);
    }

    @Then("the memory valence is below {double}")
    public void memoryValenceIsBelow(double threshold) {
        assertThat(soleMemory().get("valence").asDouble()).isLessThan(threshold);
    }

    @Then("the memory has been reactivated {int} time")
    @Then("the memory has been reactivated {int} times")
    public void memoryReactivatedExactly(int count) {
        assertThat(soleMemory().get("reactivationCount").asInt()).isEqualTo(count);
    }

    @Then("the memory has been reactivated more than {int} times")
    public void memoryReactivatedMoreThan(int count) {
        assertThat(soleMemory().get("reactivationCount").asInt()).isGreaterThan(count);
    }

    @Then("the {string} memory has been reactivated more than {int} times")
    public void kindReactivatedMoreThan(String kind, int count) {
        assertThat(memoryOfKind(kind).get("reactivationCount").asInt()).isGreaterThan(count);
    }

    @Then("the {string} memory has been reactivated {int} times")
    public void kindReactivatedExactly(String kind, int count) {
        assertThat(memoryOfKind(kind).get("reactivationCount").asInt()).isEqualTo(count);
    }

    @Then("{string} remembers nothing about {string}")
    public void remembersNothing(String owner, String subject) {
        assertThat(memoriesOf(owner)).noneMatch(memory -> memory.get("subjectId").asString().equals(subject));
    }

    @Then("{string} still holds {int} memory about {string}")
    public void stillHolds(String owner, int count, String subject) {
        assertThat(memoriesOf(owner).stream()
                .filter(memory -> memory.get("subjectId").asString().equals(subject)).count()).isEqualTo(count);
    }

    @Then("{string} has at least {int} memory recorded")
    public void hasAtLeastMemories(String owner, int count) {
        assertThat(memoriesOf(owner)).hasSizeGreaterThanOrEqualTo(count);
    }

    @Then("the relationship matrix has an entry from {string} about {string}")
    public void relationshipMatrixHasEntry(String owner, String subject) {
        JsonNode matrix = world.get("/api/v1/runs/" + world.runId() + "/relationships");
        boolean found = false;
        for (JsonNode cell : matrix) {
            if (cell.get("ownerId").asString().equals(owner) && cell.get("subjectId").asString().equals(subject)) {
                found = true;
            }
        }
        assertThat(found).withFailMessage("no relationship entry from %s about %s in %s", owner, subject, matrix)
                .isTrue();
    }

    private JsonNode soleMemory() {
        List<JsonNode> memories = memoriesOf(null);
        assertThat(memories).withFailMessage("expected exactly one memory but found %d", memories.size()).hasSize(1);
        return memories.getFirst();
    }

    private JsonNode memoryOfKind(String kind) {
        return memoriesOf(null).stream().filter(memory -> memory.get("kind").asString().equals(kind)).findFirst()
                .orElseThrow(() -> new AssertionError("no memory of kind " + kind));
    }

    private List<JsonNode> memoriesOf(String ownerId) {
        String path = "/api/v1/runs/" + world.runId() + "/memories"
                + (ownerId == null ? "" : "?ownerId=" + ownerId);
        JsonNode response = world.get(path);
        List<JsonNode> memories = new ArrayList<>();
        response.get("items").forEach(memories::add);
        return memories;
    }
}
