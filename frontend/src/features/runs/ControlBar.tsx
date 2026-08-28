import type { RunDetail, RunStatus } from "@/api/types";
import { useRunControl, useSetSpeed } from "@/api/queries";
import { useLiveSnapshot, useStreamConnected } from "@/api/liveRun";
import { Badge, Button, Stat } from "@/components/ui/primitives";
import { formatTick } from "@/lib/utils";
import { useState } from "react";

const SPEEDS = [1, 5, 20, 100, 0] as const;

function speedLabel(speed: number): string {
  return speed === 0 ? "max" : `${speed}/s`;
}

const STATUS_TONE: Record<RunStatus, "neutral" | "good" | "warning" | "critical" | "accent"> = {
  CREATED: "neutral",
  RUNNING: "good",
  PAUSED: "warning",
  STOPPED: "neutral",
  COMPLETED: "accent",
  FAILED: "critical",
};

/**
 * Transport controls for a run.
 *
 * The tick counter comes from the live stream rather than from a polled query: it is the one
 * number on the page that has to feel immediate, and at a hundred ticks a second a poll would show
 * a figure that is always visibly behind.
 */
export function ControlBar({ run, onCheckpoint }: { run: RunDetail; onCheckpoint: () => void }) {
  const runId = run.summary.id;
  const control = useRunControl(runId);
  const setSpeed = useSetSpeed(runId);
  const live = useLiveSnapshot(runId);
  const connected = useStreamConnected(runId);
  const [stepSize, setStepSize] = useState(10);

  const status = live?.status ?? run.summary.status;
  const tick = live?.tick ?? run.summary.tick;
  const running = status === "RUNNING";
  const terminal = status === "STOPPED" || status === "COMPLETED" || status === "FAILED";

  return (
    <div className="flex flex-wrap items-center gap-x-5 gap-y-3 border-b border-[var(--border)] bg-[var(--surface-1)] px-4 py-3">
      <div className="flex items-center gap-1.5">
        <Button
          variant={running ? "secondary" : "primary"}
          onClick={() => control.mutate({ action: running ? "PAUSE" : "START" })}
          disabled={terminal || control.isPending}
          title={running ? "Pause" : "Run"}
        >
          {running ? "❙❙ Pause" : "▶ Run"}
        </Button>

        <Button
          onClick={() => control.mutate({ action: "STEP", ticks: stepSize })}
          disabled={running || terminal || control.isPending}
          title={`Advance ${stepSize} ticks and stop`}
        >
          ⏭ Step
        </Button>

        <input
          aria-label="Ticks per step"
          className="tabular h-9 w-16 rounded-md border border-[var(--border)] bg-[var(--surface-0)] px-2 text-sm"
          value={stepSize}
          onChange={(event) => setStepSize(Math.max(1, Number(event.target.value.replace(/\D/g, "")) || 1))}
          inputMode="numeric"
        />

        <Button
          variant="ghost"
          onClick={() => control.mutate({ action: "STOP" })}
          disabled={terminal || control.isPending}
          title="Stop this run for good"
        >
          ■ Stop
        </Button>
      </div>

      <div className="flex items-center gap-1">
        <span className="text-xs text-[var(--text-muted)]">Speed</span>
        {SPEEDS.map((speed) => (
          <Button
            key={speed}
            size="sm"
            variant={run.summary.speed === speed ? "primary" : "ghost"}
            onClick={() => setSpeed.mutate(speed)}
            title={`Run at ${speedLabel(speed)}`}
          >
            {speedLabel(speed)}
          </Button>
        ))}
      </div>

      <Button onClick={onCheckpoint} title="Save the current state so it can be resumed or forked">
        ⚑ Checkpoint
      </Button>

      <div className="ml-auto flex items-center gap-6">
        <Stat label="Tick" testId="run-tick" value={<span className="tabular">{formatTick(tick)}</span>} />
        <Stat
          label="Actual rate"
          value={
            <span className="tabular">
              {running ? `${live?.ticksPerSecondActual ?? run.ticksPerSecondActual}/s` : "—"}
            </span>
          }
        />
        <Stat label="Memories" value={<span className="tabular">{formatTick(live?.memoryCount ?? 0)}</span>} />
        <div className="flex flex-col items-start gap-1">
          <Badge tone={STATUS_TONE[status]}>{status.toLowerCase()}</Badge>
          <span
            className="text-[10px] text-[var(--text-muted)]"
            title={connected ? "Receiving live updates" : "Not connected to the live stream"}
          >
            {connected ? "● live" : "○ offline"}
          </span>
        </div>
      </div>
    </div>
  );
}
