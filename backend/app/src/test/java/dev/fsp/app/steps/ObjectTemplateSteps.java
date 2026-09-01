package dev.fsp.app.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.app.support.World;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Shared object types: creating them, publishing them, and telling versions apart. */
public class ObjectTemplateSteps {

    @Autowired
    private World world;

    private String templateId;

    /** A minimal but valid type: two variables and a plain exponential forgetting curve. */
    private static Map<String, Object> draft(String id, List<String> variableNames) {
        Map<String, Object> decay = Map.of("kind", "exponential", "halfLife", 40.0,
                "common", Map.of("floor", 0.0, "interference", 0.0));
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("defaultDecay", decay);
        memory.put("retrievalThreshold", 0.05);
        memory.put("capacity", 0);
        memory.put("pruneForgotten", false);
        memory.put("similarityThreshold", 0.6);

        List<Map<String, Object>> variables = variableNames.stream().map(name -> {
            Map<String, Object> variable = new LinkedHashMap<>();
            variable.put("name", name);
            variable.put("label", name);
            variable.put("kind", "STOCK");
            variable.put("initial", 0.5);
            variable.put("min", 0.0);
            variable.put("max", 1.0);
            return variable;
        }).toList();

        Map<String, Object> type = new LinkedHashMap<>();
        type.put("id", id);
        type.put("label", id);
        type.put("variables", variables);
        type.put("memory", memory);
        type.put("defaultTags", List.of());
        type.put("defaultFeatures", Map.of());
        return type;
    }

    private void create(String name, Map<String, Object> draft) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "");
        body.put("draft", draft);
        templateId = world.post("/api/v1/object-templates", body).get("id").asString();
    }

    @When("an object template called {string} is created")
    public void anObjectTemplateIsCreated(String name) {
        create(name, draft("worker", List.of("morale", "load")));
    }

    @When("an object template with no draft is created")
    public void anObjectTemplateWithNoDraftIsCreated() {
        create("Empty", null);
    }

    @Given("an object template called {string} published as version 1")
    public void anObjectTemplatePublishedAsVersionOne(String name) {
        create(name, draft("worker", List.of("morale", "load")));
        world.post("/api/v1/object-templates/" + templateId + "/versions", null);
    }

    @When("the template is published")
    public void theTemplateIsPublished() {
        world.post("/api/v1/object-templates/" + templateId + "/versions", null);
    }

    @When("the template is published again")
    public void theTemplateIsPublishedAgain() {
        theTemplateIsPublished();
    }

    @When("the template gains a variable and is published again")
    public void theTemplateGainsAVariable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "changed");
        body.put("description", "");
        body.put("draft", draft("worker", List.of("morale", "load", "skill")));
        world.put("/api/v1/object-templates/" + templateId + "/draft", body);
        theTemplateIsPublished();
    }

    @Then("the template has {int} published version")
    public void theTemplateHasVersions(int expected) {
        assertThat(world.get("/api/v1/object-templates/" + templateId + "/versions").size())
                .isEqualTo(expected);
    }

    @Then("the template has {int} published versions")
    public void theTemplateHasSeveralVersions(int expected) {
        theTemplateHasVersions(expected);
    }

    @Then("version {int} of the template still has {int} variables")
    public void versionStillHasVariables(int version, int expected) {
        // The point of the whole feature: publishing a newer version must leave the older one, and
        // therefore any system pinned to it, exactly as it was.
        JsonNode versions = world.get("/api/v1/object-templates/" + templateId + "/versions");
        JsonNode wanted = null;
        for (JsonNode candidate : versions) {
            if (candidate.get("version").asInt() == version) {
                wanted = candidate;
            }
        }
        assertThat(wanted).as("version %d", version).isNotNull();
        assertThat(wanted.get("typeSpec").get("variables").size()).isEqualTo(expected);
    }

    @Then("the difference between version {int} and version {int} reports {string}")
    public void theDifferenceReports(int from, int to, String change) {
        JsonNode report = world.get(
                "/api/v1/object-templates/" + templateId + "/versions/" + from + "/diff/" + to);
        assertThat(report.get("identical").asBoolean()).isFalse();
        boolean found = false;
        for (JsonNode entry : report.get("changes")) {
            if (entry.get("change").asString().equals(change)) {
                found = true;
            }
        }
        assertThat(found).as("a change of kind '%s'", change).isTrue();
    }

    @Then("publishing that template fails")
    public void publishingThatTemplateFails() {
        world.expectFailure(() -> world.post("/api/v1/object-templates/" + templateId + "/versions", null));
    }
}
