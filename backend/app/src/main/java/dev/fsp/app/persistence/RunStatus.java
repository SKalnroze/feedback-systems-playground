package dev.fsp.app.persistence;

/** Lifecycle of a simulation run. */
public enum RunStatus {

    /** Created but never started. */
    CREATED,

    /** Actively stepping in the background. */
    RUNNING,

    /** Stopped stepping but still resident, and resumable. */
    PAUSED,

    /** Deliberately ended by the user; can still be forked from a checkpoint. */
    STOPPED,

    /** Reached the tick limit its spec declared. */
    COMPLETED,

    /** Stopped because a tick threw. The error is recorded on the run. */
    FAILED;

    /** Whether a run in this state can be started or resumed. */
    public boolean isResumable() {
        return this == CREATED || this == PAUSED;
    }

    /** Whether the run has finished for good. */
    public boolean isTerminal() {
        return this == STOPPED || this == COMPLETED || this == FAILED;
    }
}
