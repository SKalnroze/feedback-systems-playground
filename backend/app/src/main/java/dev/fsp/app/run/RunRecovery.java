package dev.fsp.app.run;

import dev.fsp.app.persistence.RunRecords.Run;
import dev.fsp.app.persistence.RunRepository;
import dev.fsp.app.persistence.RunStatus;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Brings back runs that were still going when the process last stopped.
 *
 * <p>They come back paused rather than running. A long simulation that silently resumed on every
 * restart would be a surprise, and the user may well be restarting precisely because they want to
 * look at it before it goes any further.
 */
@Component
public class RunRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunRecovery.class);

    private final RunRepository runs;
    private final RunService runService;
    private final RunProperties properties;

    public RunRecovery(RunRepository runs, RunService runService, RunProperties properties) {
        this.runs = runs;
        this.runService = runService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.recoverOnStartup()) {
            return;
        }
        List<Run> interrupted = runs.findByStatus(RunStatus.RUNNING);
        if (interrupted.isEmpty()) {
            return;
        }
        log.info("recovering {} interrupted run(s)", interrupted.size());
        for (Run run : interrupted) {
            try {
                runs.updateStatus(run.id(), RunStatus.PAUSED);
                runService.ensureHosted(run.id());
            } catch (RuntimeException e) {
                // One unreadable run must not stop the application from starting.
                log.warn("could not recover run {}", run.id(), e);
                runs.markFailed(run.id(), "recovery failed: " + e);
            }
        }
    }
}
