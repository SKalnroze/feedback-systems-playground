package dev.fsp.app.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.app.support.World;
import io.cucumber.java.en.Then;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Assertions about the recorded history of a run. */
public class ObservabilitySteps {

    @Autowired
    private World world;

    private JsonNode lastSeriesResponse;

    @Then("no events have fired")
    public void noEventsFired() {
        assertThat(logEntries("EVENT")).isEmpty();
    }

    @Then("the event {string} has fired {int} time")
    @Then("the event {string} has fired {int} times")
    public void eventFiredExactly(String eventId, int count) {
        assertThat(countEventOccurrences(eventId)).isEqualTo(count);
    }

    @Then("the event {string} has fired between {int} and {int} times")
    public void eventFiredBetween(String eventId, int low, int high) {
        assertThat(countEventOccurrences(eventId)).isBetween(low, high);
    }

    @Then("the log contains at least {int} entries of type {string}")
    public void logContainsEntries(int count, String type) {
        assertThat(logEntries(type)).hasSizeGreaterThanOrEqualTo(count);
    }

    @Then("the log mentions the event {string}")
    public void logMentionsEvent(String eventId) {
        assertThat(logEntries("EVENT")).anyMatch(entry -> entry.get("detail").asString().contains(eventId));
    }

    @Then("the series {string} has at least {int} points")
    public void seriesHasPoints(String key, int count) {
        assertThat(pointsOf(fetchSeries(List.of(key), 0), key)).hasSizeGreaterThanOrEqualTo(count);
    }

    @Then("the last value of {string} is below {double}")
    public void lastValueIsBelow(String key, double threshold) {
        List<JsonNode> points = pointsOf(fetchSeries(List.of(key), 0), key);
        assertThat(points).isNotEmpty();
        assertThat(points.getLast().get(1).asDouble()).isLessThan(threshold);
    }

    @Then("fetching the series {string} and {string} returns {int} series")
    public void fetchingTwoSeries(String first, String second, int count) {
        JsonNode response = fetchSeries(List.of(first, second), 0);
        assertThat(response.get("series")).hasSize(count);
    }

    @Then("fetching {string} with resolution {int} returns fewer than {int} points")
    public void fetchWithResolution(String key, int resolution, int maxPoints) {
        lastSeriesResponse = fetchSeries(List.of(key), resolution);
        assertThat(pointsOf(lastSeriesResponse, key)).hasSizeLessThan(maxPoints);
    }

    @Then("those points are marked as bucketed")
    public void pointsAreBucketed() {
        assertThat(lastSeriesResponse.get("series").get(0).get("bucketed").asBoolean()).isTrue();
    }

    @Then("the series catalogue includes {string}")
    public void catalogueIncludes(String key) {
        JsonNode catalogue = world.get("/api/v1/runs/" + world.runId() + "/series");
        List<String> keys = new ArrayList<>();
        catalogue.forEach(entry -> keys.add(entry.get("seriesKey").asString()));
        assertThat(keys).contains(key);
    }

    private JsonNode fetchSeries(List<String> keys, int resolution) {
        String query = "keys=" + String.join(",", keys) + "&resolution=" + resolution;
        return world.get("/api/v1/runs/" + world.runId() + "/series/data?" + query);
    }

    private static List<JsonNode> pointsOf(JsonNode response, String key) {
        for (JsonNode series : response.get("series")) {
            if (series.get("key").asString().equals(key)) {
                List<JsonNode> points = new ArrayList<>();
                series.get("points").forEach(points::add);
                return points;
            }
        }
        return List.of();
    }

    /** Counts occurrences of one event in the log, since each firing writes exactly one entry. */
    private int countEventOccurrences(String eventId) {
        return (int) logEntries("EVENT").stream()
                .filter(entry -> eventId.equals(entry.get("subject").asString())).count();
    }

    private List<JsonNode> logEntries(String type) {
        JsonNode response = world
                .get("/api/v1/runs/" + world.runId() + "/log?limit=5000" + (type == null ? "" : "&type=" + type));
        List<JsonNode> entries = new ArrayList<>();
        response.get("items").forEach(entries::add);
        return entries;
    }
}
