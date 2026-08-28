package dev.fsp.app.run;

import dev.fsp.app.persistence.MetricRepository;
import dev.fsp.app.persistence.RunLogRepository;
import dev.fsp.app.persistence.RunRepository;
import dev.fsp.app.persistence.RunStatus;
import dev.fsp.engine.Engine;
import dev.fsp.engine.state.SimulationState;
import jakarta.annotation.PreDestroy;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds every run that is currently resident, each on its own thread.
 *
 * <p>Virtual threads, because a run spends most of its life parked - paused, or sleeping between
 * ticks to hit a requested pace - and platform threads would cap how many simulations could sit in
 * the background at once for no benefit.
 */
@Component
public class RunEngineHost {

    private static final Logger log = LoggerFactory.getLogger(RunEngineHost.class);

    private final Map<UUID, RunSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Thread> threads = new ConcurrentHashMap<>();

    private final RunRepository runs;
    private final MetricRepository metrics;
    private final RunLogRepository logs;
    private final RunProperties properties;
    private final MetricsProperties metricsProperties;
    private final RunBroadcaster broadcaster;

    public RunEngineHost(RunRepository runs, MetricRepository metrics, RunLogRepository logs, RunProperties properties,
            MetricsProperties metricsProperties, RunBroadcaster broadcaster) {
        this.runs = runs;
        this.metrics = metrics;
        this.logs = logs;
        this.properties = properties;
        this.metricsProperties = metricsProperties;
        this.broadcaster = broadcaster;
    }

    /** Makes a run resident and starts its thread. Returns the existing session if already loaded. */
    public RunSession host(UUID runId, Engine engine, SimulationState state, RunStatus status, double speed) {
        RunSession existing = sessions.get(runId);
        if (existing != null) {
            return existing;
        }
        RunSession session = new RunSession(runId, engine, state, status, effectiveSpeed(speed), runs, metrics, logs,
                properties, metricsProperties, broadcaster);
        sessions.put(runId, session);

        Thread thread = Thread.ofVirtual().name("fsp-run-" + runId).unstarted(session::loop);
        threads.put(runId, thread);
        thread.start();
        log.info("hosting run {} at tick {}", runId, state.tick());
        return session;
    }

    public Optional<RunSession> session(UUID runId) {
        return Optional.ofNullable(sessions.get(runId));
    }

    public boolean isHosted(UUID runId) {
        return sessions.containsKey(runId);
    }

    public Collection<RunSession> sessions() {
        return sessions.values();
    }

    /** Stops a run's thread and forgets it. The run's stored state is left alone. */
    public void evict(UUID runId) {
        RunSession session = sessions.remove(runId);
        Thread thread = threads.remove(runId);
        if (session != null) {
            session.shutdown();
        }
        if (thread != null) {
            try {
                thread.join(java.time.Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        broadcaster.closeAll(runId);
    }

    private double effectiveSpeed(double requested) {
        return requested <= 0 ? properties.maxTicksPerSecond()
                : Math.min(requested, properties.maxTicksPerSecond());
    }

    /**
     * Brings every resident run to a stop on shutdown, giving each a chance to flush what it has
     * so far. A run interrupted mid-flight comes back paused at its last checkpoint, so the worst
     * case is losing the ticks since then, not the run.
     */
    @PreDestroy
    void shutDownAll() {
        log.info("stopping {} resident run(s)", sessions.size());
        sessions.keySet().forEach(this::evict);
    }
}
