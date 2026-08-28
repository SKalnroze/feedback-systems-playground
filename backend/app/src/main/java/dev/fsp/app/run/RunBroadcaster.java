package dev.fsp.app.run;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes live run updates to connected browsers.
 *
 * <p>Server-sent events rather than websockets: the traffic is entirely one-way, SSE reconnects by
 * itself, and it survives an ordinary reverse proxy without protocol upgrades. Control still goes
 * over plain HTTP, where it can be authorised and logged like anything else.
 *
 * <p>Emitters are held per run and pruned as they fail. A browser that has gone away must never be
 * able to slow down or stop a simulation, so every send failure is swallowed and the subscriber
 * simply dropped.
 */
@Component
public class RunBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RunBroadcaster.class);

    /** Long enough to survive an idle pause, short enough that dead sockets do not accumulate. */
    public static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final Map<UUID, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final SnapshotCodec codec;

    public RunBroadcaster(SnapshotCodec codec) {
        this.codec = codec;
    }

    SnapshotCodec codec() {
        return codec;
    }

    public SseEmitter subscribe(UUID runId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        List<SseEmitter> forRun = subscribers.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>());
        forRun.add(emitter);

        emitter.onCompletion(() -> forRun.remove(emitter));
        emitter.onTimeout(() -> {
            forRun.remove(emitter);
            emitter.complete();
        });
        emitter.onError(error -> forRun.remove(emitter));
        return emitter;
    }

    public void publishTick(UUID runId, RunSnapshot snapshot) {
        publish(runId, "tick", snapshot);
    }

    public void publishStatus(UUID runId, RunSnapshot snapshot) {
        publish(runId, "status", snapshot);
    }

    /** Number of browsers currently watching a run, which the UI shows on the run list. */
    public int subscriberCount(UUID runId) {
        List<SseEmitter> forRun = subscribers.get(runId);
        return forRun == null ? 0 : forRun.size();
    }

    /** Ends every subscription for a run, e.g. when it is deleted. */
    public void closeAll(UUID runId) {
        List<SseEmitter> forRun = subscribers.remove(runId);
        if (forRun == null) {
            return;
        }
        for (SseEmitter emitter : forRun) {
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // Already gone; nothing to do.
            }
        }
    }

    private void publish(UUID runId, String eventName, RunSnapshot snapshot) {
        List<SseEmitter> forRun = subscribers.get(runId);
        if (forRun == null || forRun.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : forRun) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(snapshot));
            } catch (IOException | IllegalStateException e) {
                // The subscriber has gone. Drop it quietly: a closed browser tab is not an error,
                // and it must never interfere with the run itself.
                forRun.remove(emitter);
                log.debug("dropped subscriber for run {}", runId, e);
            }
        }
    }
}
