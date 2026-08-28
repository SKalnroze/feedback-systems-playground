package dev.fsp.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fsp.engine.event.Effect;
import dev.fsp.engine.event.EventGenerator;
import dev.fsp.engine.event.TargetSelector;
import dev.fsp.engine.expr.NumExpr;
import dev.fsp.engine.expr.Scope;
import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.memory.MemorySettings;
import dev.fsp.engine.memory.MemoryTrigger;
import dev.fsp.engine.spec.EventSpec;
import dev.fsp.engine.spec.LinkSpec;
import dev.fsp.engine.spec.ObjectSpec;
import dev.fsp.engine.spec.ObjectTypeSpec;
import dev.fsp.engine.spec.SimulationSettings;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.spec.VariableSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared state and helpers for the acceptance scenarios.
 *
 * <p>Scenarios drive the application through its HTTP API, exactly as the frontend does, so that a
 * passing suite is evidence the product works rather than that its internals are wired together.
 * Specs are still built with the engine's own builders: composing them as raw JSON would test the
 * test's ability to write JSON more than anything else.
 */
@Component
public class World {

    /** Long enough for a slow CI machine, short enough that a hang fails rather than hangs. */
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /**
     * Tick at which scenario-seeded memories are laid down. Events fire from tick one onwards, so
     * a memory can never be created at tick zero, and scenarios that talk about a memory's age have
     * to count from here.
     */
    public static final long MEMORY_SEED_TICK = 1L;

    private final ObjectMapper json;
    private final Environment environment;
    private RestClient http;

    // Spec under construction
    private String systemName = "unnamed system";
    private MemorySettings memorySettings = new MemorySettings(DecayModel.Exponential.ofHalfLife(50.0), 0.05, 0, false,
            0.6);
    private final List<ObjectSpec> objects = new ArrayList<>();
    private final List<EventSpec> events = new ArrayList<>();
    private final List<MemoryTrigger> triggers = new ArrayList<>();
    private final List<LinkSpec> links = new ArrayList<>();
    private final List<VariableSpec> globals = new ArrayList<>();

    // Scenario state
    private String systemId;
    private String versionId;
    private String runId;
    private String secondRunId;
    private String checkpointId;
    private final Map<String, String> namedCheckpoints = new HashMap<>();
    private final Map<String, String> digests = new HashMap<>();
    private final List<String> createdRunIds = new ArrayList<>();
    private RestClientResponseException lastFailure;
    private JsonNode lastResponse;

    @Autowired
    public World(ObjectMapper json, Environment environment) {
        this.json = json;
        this.environment = environment;
    }

    /** Wipes scenario state. Called from a hook before each scenario. */
    public void reset() {
        systemName = "unnamed system";
        memorySettings = new MemorySettings(DecayModel.Exponential.ofHalfLife(50.0), 0.05, 0, false, 0.6);
        objects.clear();
        events.clear();
        triggers.clear();
        links.clear();
        globals.clear();
        systemId = null;
        versionId = null;
        runId = null;
        secondRunId = null;
        checkpointId = null;
        namedCheckpoints.clear();
        digests.clear();
        createdRunIds.clear();
        lastFailure = null;
        lastResponse = null;
        // Read at reset rather than injected: the port only exists once the web server is up,
        // which is after this bean is constructed.
        int port = environment.getRequiredProperty("local.server.port", Integer.class);
        http = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // --- spec construction ---------------------------------------------------------------------

    public void namedSystem(String name) {
        this.systemName = name;
    }

    public void addPerson(String id) {
        objects.add(ObjectSpec.of(id, "person"));
    }

    public void memorySettings(MemorySettings settings) {
        this.memorySettings = settings;
    }

    public MemorySettings memorySettings() {
        return memorySettings;
    }

    public void addEvent(EventSpec event) {
        events.add(event);
    }

    public void addTrigger(MemoryTrigger trigger) {
        triggers.add(trigger);
    }

    public void addLink(LinkSpec link) {
        links.add(link);
    }

    public void addGlobal(VariableSpec variable) {
        globals.add(variable);
    }

    /** An event that lays down a memory from one named person about another. */
    public EventSpec memoryEvent(String id, long tick, String owner, String subject, String kind, double strength,
            double valence) {
        return EventSpec.of(id, EventGenerator.FixedSchedule.at(tick), new TargetSelector.NamedPair(owner, subject),
                List.of(new Effect.InjectMemory(Scope.SELF, Scope.TARGET, kind, NumExpr.of(strength),
                        NumExpr.of(valence), 0.8, Map.of("encounter", 1.0), null)));
    }

    /** The spec as currently configured. */
    public SystemSpec spec() {
        SystemSpec.Builder builder = SystemSpec.builder(slug(systemName)).name(systemName)
                .objectType(personType(memorySettings))
                .settings(new SimulationSettings(1, 0, 0L, 1, false));
        objects.forEach(builder::object);
        events.forEach(builder::event);
        triggers.forEach(builder::trigger);
        links.forEach(builder::link);
        globals.forEach(builder::globalVariable);
        return builder.build();
    }

    public static ObjectTypeSpec personType(MemorySettings memory) {
        return new ObjectTypeSpec("person", "Person",
                List.of(VariableSpec.unitStock("trust", 0.5), VariableSpec.unitStock("resentment", 0.0),
                        VariableSpec.unitStock("withdrawal", 0.0)),
                memory, Set.of(), Map.of());
    }

    // --- API calls -----------------------------------------------------------------------------

    /** Creates the system from the current spec if it does not exist yet, then returns its id. */
    public String ensureSystem() {
        if (systemId == null) {
            JsonNode created = post("/api/v1/systems",
                    Map.of("name", systemName, "description", "", "draft", raw(spec())));
            systemId = created.get("id").asString();
        } else {
            put("/api/v1/systems/" + systemId + "/draft",
                    Map.of("name", systemName, "description", "", "draft", raw(spec())));
        }
        return systemId;
    }

    public String publish() {
        JsonNode version = post("/api/v1/systems/" + ensureSystem() + "/versions", null);
        versionId = version.get("id").asString();
        return versionId;
    }

    /** Creates a run against the freshly published current spec. */
    public String createRun(Long seed) {
        publish();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemVersionId", versionId);
        body.put("name", systemName + " run");
        // As a string, matching the API: a 64-bit seed sent as a JSON number would be rounded.
        body.put("seed", seed == null ? null : Long.toString(seed));
        body.put("speed", 0.0);
        body.put("autoStart", false);
        JsonNode run = post("/api/v1/runs", body);
        runId = run.get("id").asString();
        createdRunIds.add(runId);
        return runId;
    }

    public String runId() {
        return runId == null ? createRun(4242L) : runId;
    }

    /**
     * Stops every run this scenario started.
     *
     * <p>Runs keep going in the background by design, which is the right behaviour for the product
     * and the wrong thing to leave behind in a test: a scenario that starts a run at two hundred
     * ticks a second and walks away leaves it competing with every scenario that follows. Left
     * unchecked the suite slows down as it goes and starts failing on timing rather than on
     * behaviour.
     */
    public void stopCreatedRuns() {
        for (String id : createdRunIds) {
            try {
                post("/api/v1/runs/" + id + "/control", Map.of("action", "STOP"));
            } catch (RuntimeException ignored) {
                // Already finished, already stopped, or deleted: nothing left to wind down.
            }
        }
        createdRunIds.clear();
    }

    public void useRun(String id) {
        this.runId = id;
    }

    public String secondRunId() {
        return secondRunId;
    }

    public void secondRunId(String id) {
        this.secondRunId = id;
    }

    public String checkpointId() {
        return checkpointId;
    }

    public void checkpointId(String id) {
        this.checkpointId = id;
    }

    public void rememberCheckpoint(String label, String id) {
        namedCheckpoints.put(label, id);
    }

    public String checkpointNamed(String label) {
        return namedCheckpoints.get(label);
    }

    public void rememberDigest(String key, String digest) {
        digests.put(key, digest);
    }

    public String digest(String key) {
        return digests.get(key);
    }

    /** Steps a run and waits until it has actually got there. */
    public void step(String id, int ticks) {
        long before = tickOf(id);
        post("/api/v1/runs/" + id + "/control", Map.of("action", "STEP", "ticks", ticks));
        awaitTick(id, before + ticks);
    }

    public void awaitTick(String id, long target) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (tickOf(id) >= target) {
                return;
            }
            sleep(10);
        }
        assertThat(tickOf(id)).withFailMessage("run %s never reached tick %d", id, target)
                .isGreaterThanOrEqualTo(target);
    }

    /** Waits for a run to report one of the given statuses. */
    public void awaitStatus(String id, String... statuses) {
        List<String> wanted = List.of(statuses);
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (wanted.contains(statusOf(id))) {
                return;
            }
            sleep(10);
        }
        assertThat(statusOf(id)).isIn((Object[]) statuses);
    }

    public long tickOf(String id) {
        return get("/api/v1/runs/" + id).get("summary").get("tick").asLong();
    }

    public String statusOf(String id) {
        return get("/api/v1/runs/" + id).get("summary").get("status").asString();
    }

    public JsonNode runDetail(String id) {
        return get("/api/v1/runs/" + id);
    }

    // --- HTTP plumbing -------------------------------------------------------------------------

    public JsonNode get(String path) {
        lastResponse = read(http.get().uri(path).retrieve().body(String.class));
        return lastResponse;
    }

    public JsonNode post(String path, Object body) {
        var request = http.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        String payload = body == null ? "{}" : json.writeValueAsString(body);
        lastResponse = read(request.body(payload).retrieve().body(String.class));
        return lastResponse;
    }

    public JsonNode patch(String path, Object body) {
        lastResponse = read(http.patch().uri(path).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body)).retrieve().body(String.class));
        return lastResponse;
    }

    public JsonNode put(String path, Object body) {
        lastResponse = read(http.put().uri(path).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body)).retrieve().body(String.class));
        return lastResponse;
    }

    /** Runs a call that is expected to fail, keeping the failure for a later assertion. */
    public void expectFailure(Runnable call) {
        try {
            call.run();
            lastFailure = null;
        } catch (RestClientResponseException e) {
            lastFailure = e;
        }
    }

    public RestClientResponseException lastFailure() {
        return lastFailure;
    }

    public JsonNode lastResponse() {
        return lastResponse;
    }

    public ObjectMapper json() {
        return json;
    }

    /** Pre-serialised value, so nested engine types go through the configured mapper unchanged. */
    private Object raw(SystemSpec spec) {
        return json.readTree(json.writeValueAsString(spec));
    }

    private JsonNode read(String body) {
        return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String slug(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }
}
