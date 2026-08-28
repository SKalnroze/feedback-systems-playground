package dev.fsp.app.run;

/**
 * An instruction for a running simulation, applied at a tick boundary.
 *
 * <p>Queued rather than applied directly. A control that took effect halfway through a tick would
 * make a run's outcome depend on when a button was pressed, which would quietly destroy
 * reproducibility - the one property the whole engine is built around.
 */
public sealed interface RunCommand {

    record Start() implements RunCommand {
    }

    record Pause() implements RunCommand {
    }

    record Resume() implements RunCommand {
    }

    record Stop() implements RunCommand {
    }

    /** Advance exactly {@code ticks} ticks, then pause again. */
    record Step(int ticks) implements RunCommand {

        public Step {
            if (ticks <= 0) {
                throw new IllegalArgumentException("ticks must be positive: " + ticks);
            }
        }
    }

    /** @param ticksPerSecond target pace; zero or less means as fast as the machine allows */
    record SetSpeed(double ticksPerSecond) implements RunCommand {
    }

    /** Write a checkpoint at the next tick boundary. */
    record Checkpoint(String label) implements RunCommand {
    }
}
