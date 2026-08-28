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
