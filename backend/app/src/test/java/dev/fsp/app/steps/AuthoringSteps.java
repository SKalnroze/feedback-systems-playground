package dev.fsp.app.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.app.support.World;
import dev.fsp.engine.spec.LinkSpec;
import dev.fsp.engine.spec.VariableRef;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Steps for creating, validating and publishing system definitions. */
public class AuthoringSteps {

    @Autowired
    private World world;

    private String createdSystemId;
    private JsonNode validationReport;

    @When("a system called {string} is created")
    public void aSystemIsCreated(String name) {
        world.namedSystem(name);
        createdSystemId = world.ensureSystem();
    }

    /**
     * Posts a draft in the shape the editor produced before object counts and group sampling
     * existed: no `count` on an object, no `sampleGroupMembers` in settings.
     */
    @When("a system is created from a draft written before groups existed")
    public void aSystemFromAnOlderDraft() {
        java.util.Map<String, Object> variable = new java.util.LinkedHashMap<>();
        variable.put("name", "trust");
        variable.put("label", "trust");
        variable.put("kind", "STOCK");
        variable.put("initial", 0.5);
        variable.put("min", 0.0);
        variable.put("max", 1.0);

        java.util.Map<String, Object> memory = new java.util.LinkedHashMap<>();
        memory.put("defaultDecay", java.util.Map.of("kind", "exponential", "halfLife", 40.0,
                "common", java.util.Map.of("floor", 0.0, "interference", 0.0)));
        memory.put("retrievalThreshold", 0.05);
        memory.put("capacity", 0);
        memory.put("pruneForgotten", false);
        memory.put("similarityThreshold", 0.6);

        java.util.Map<String, Object> type = new java.util.LinkedHashMap<>();
        type.put("id", "person");
        type.put("label", "person");
        type.put("variables", java.util.List.of(variable));
        type.put("memory", memory);
        type.put("defaultTags", java.util.List.of());
        type.put("defaultFeatures", java.util.Map.of());

        java.util.Map<String, Object> object = new java.util.LinkedHashMap<>();
        object.put("id", "ana");
        object.put("typeId", "person");
        object.put("label", "ana");
        object.put("variables", java.util.Map.of());
        object.put("tags", java.util.List.of());
        object.put("features", java.util.Map.of());
        // Deliberately no "count".

        java.util.Map<String, Object> settings = new java.util.LinkedHashMap<>();
        settings.put("metricSampleInterval", 1);
        settings.put("autoCheckpointInterval", 250);
        settings.put("maxTicks", 0);
        settings.put("interactionsPerTick", 1);
        settings.put("sampleMemoryStrength", false);
        // Deliberately no "sampleGroupMembers".

        java.util.Map<String, Object> draft = new java.util.LinkedHashMap<>();
        draft.put("id", "legacy");
        draft.put("name", "legacy");
        draft.put("description", "");
        draft.put("moduleIds", java.util.List.of());
        draft.put("objectTypes", java.util.List.of(type));
        draft.put("objects", java.util.List.of(object));
        draft.put("globalVariables", java.util.List.of());
        draft.put("links", java.util.List.of());
        draft.put("events", java.util.List.of());
        draft.put("triggers", java.util.List.of());
        draft.put("interactions", java.util.List.of());
        draft.put("settings", settings);

        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "legacy");
        body.put("description", "");
        body.put("draft", draft);
        createdSystemId = world.post("/api/v1/systems", body).get("id").asString();

    }

    @Then("that system reports one instance per object")
    public void thatSystemReportsOneInstancePerObject() {
        var objects = world.get("/api/v1/systems/" + createdSystemId).get("draft").get("objects");
        assertThat(objects.get(0).get("count").asInt())
                .as("an object stored without a count stands for exactly one instance")
                .isEqualTo(1);
    }

    @Given("the draft has a link from a variable that does not exist")
    public void draftHasDanglingLink() {
        world.addLink(LinkSpec.of("dangling", new VariableRef.Global("nowhere"),
                new VariableRef.OfObject("ana", "trust"), 0.5));
    }

    @Given("the draft has a reinforcing loop between two variables")
    public void draftHasReinforcingLoop() {
        world.addLink(LinkSpec.of("resentment-to-withdrawal", new VariableRef.OfObject("ana", "resentment"),
                new VariableRef.OfObject("ana", "withdrawal"), 0.2));
        world.addLink(LinkSpec.of("withdrawal-to-resentment", new VariableRef.OfObject("ana", "withdrawal"),
                new VariableRef.OfObject("ana", "resentment"), 0.2));
    }

    @When("the draft is published")
    @When("the draft is published again")
    public void theDraftIsPublished() {
        createdSystemId = world.ensureSystem();
        world.publish();
    }

    @When("publishing is attempted")
    public void publishingIsAttempted() {
        createdSystemId = world.ensureSystem();
        world.expectFailure(world::publish);
    }

    @When("the draft is validated")
    public void theDraftIsValidated() {
        createdSystemId = world.ensureSystem();
        validationReport = world.get("/api/v1/systems/" + createdSystemId + "/validation");
    }

    @When("a system is created from the preset {string}")
    public void aSystemFromPreset(String presetId) {
        JsonNode created = world.post("/api/v1/systems/from-preset", Map.of("presetId", presetId));
        createdSystemId = created.get("id").asString();
    }

    @Then("the system appears in the list of systems")
    public void theSystemAppearsInTheList() {
        JsonNode systems = world.get("/api/v1/systems");
        boolean found = false;
        for (JsonNode system : systems) {
            if (system.get("id").asString().equals(createdSystemId)) {
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Then("the system has no published versions")
    public void noPublishedVersions() {
        assertThat(world.get("/api/v1/systems/" + createdSystemId + "/versions")).isEmpty();
    }

    @Then("the system has {int} published version")
    @Then("the system has {int} published versions")
    public void publishedVersionCount(int count) {
        assertThat(world.get("/api/v1/systems/" + createdSystemId + "/versions")).hasSize(count);
    }

    @Then("the published version is version {int}")
    public void publishedVersionNumber(int version) {
        assertThat(world.get("/api/v1/systems/" + createdSystemId + "/versions").get(0).get("version").asInt())
                .isEqualTo(version);
    }

    @Then("publishing fails with a validation error mentioning {string}")
    public void publishingFails(String fragment) {
        assertThat(world.lastFailure()).isNotNull();
        assertThat(world.lastFailure().getStatusCode().value()).isEqualTo(422);
        assertThat(world.lastFailure().getResponseBodyAsString()).contains(fragment);
    }

    @Then("the validation reports {int} feedback loop")
    @Then("the validation reports {int} feedback loops")
    public void validationReportsLoops(int count) {
        assertThat(validationReport.get("loops")).hasSize(count);
    }

    @Then("that loop is reinforcing")
    public void thatLoopIsReinforcing() {
        assertThat(validationReport.get("loops").get(0).get("polarity").asString()).isEqualTo("REINFORCING");
    }

    @Then("the draft has {int} objects")
    public void theDraftHasObjects(int count) {
        JsonNode system = world.get("/api/v1/systems/" + createdSystemId);
        assertThat(system.get("draft").get("objects")).hasSize(count);
    }

    @Then("the draft is valid")
    public void theDraftIsValid() {
        JsonNode report = world.get("/api/v1/systems/" + createdSystemId + "/validation");
        assertThat(report.get("valid").asBoolean())
                .withFailMessage("expected a valid draft but got %s", report).isTrue();
    }
}
