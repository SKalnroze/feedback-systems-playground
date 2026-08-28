import type { Checkpoint, LogEntry, MemoryView, RunSummary } from "@/api/types";
import { useCheckpoints, useMemories, useRelationships, useRestore, useRunLog } from "@/api/queries";
import { RelationshipHeatmap } from "@/components/charts/RelationshipHeatmap";
import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  Select,
  Spinner,
} from "@/components/ui/primitives";
import { cn, formatBytes, formatNumber, formatTick } from "@/lib/utils";
import { Link } from "@tanstack/react-router";
import { useState } from "react";

const LOG_TYPES = ["", "EVENT", "REACTIVATION", "INTERACTION", "CHECKPOINT", "CONTROL"] as const;

const LOG_TONE: Record<string, "neutral" | "good" | "warning" | "critical" | "accent"> = {
  EVENT: "accent",
  REACTIVATION: "warning",
  INTERACTION: "neutral",
  CHECKPOINT: "good",
  CONTROL: "neutral",
};

/**
 * The run log.
 *
 * This is what makes a chart movement explicable. A line dropping at tick 412 is a fact; "ana
 * clashed with ben" at tick 412 is an explanation, and clicking the entry moves every chart's
 * cursor to that tick.
 */
export function EventLogPanel({
  runId,
  live,
  onTickSelect,
}: {
  runId: string;
  live: boolean;
  onTickSelect: (tick: number) => void;
}) {
  const [type, setType] = useState<string>("");
  const log = useRunLog(runId, type || undefined, 200, live ? 2000 : false);

  return (
    <Card className="flex min-h-0 flex-col">
      <CardHeader
        title="What happened"
        subtitle="Newest first"
        actions={
          <>
            {log.isFetching ? <Spinner /> : null}
            <Select
              className="h-7 text-xs"
              value={type}
              onChange={(event) => setType(event.target.value)}
              aria-label="Filter log by type"
            >
              {LOG_TYPES.map((option) => (
                <option key={option} value={option}>
                  {option === "" ? "everything" : option.toLowerCase()}
                </option>
              ))}
            </Select>
          </>
        }
      />
      <div className="min-h-0 flex-1 overflow-auto">
        {log.data && log.data.items.length > 0 ? (
          <ul className="divide-y divide-[var(--border)] text-xs">
            {log.data.items.map((entry: LogEntry) => (
              <li key={entry.id}>
                <button
                  type="button"
                  onClick={() => onTickSelect(entry.tick)}
                  className="flex w-full items-start gap-2 px-3 py-1.5 text-left hover:bg-[var(--surface-2)]"
                >
                  <span className="tabular w-14 shrink-0 text-[var(--text-muted)]">
                    {formatTick(entry.tick)}
                  </span>
                  <Badge tone={LOG_TONE[entry.type] ?? "neutral"}>{entry.type.toLowerCase()}</Badge>
                  <span className="min-w-0 flex-1 text-[var(--text-secondary)]">{entry.detail}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState title="Nothing logged yet" description="Step the run to see what it does." />
        )}
      </div>
    </Card>
  );
}

/** What one object currently remembers, and how vividly. */
export function MemoryPanel({ runId, objectIds }: { runId: string; objectIds: string[] }) {
  const [ownerId, setOwnerId] = useState<string>("");
  const memories = useMemories(runId, ownerId || undefined);

  return (
    <Card className="flex min-h-0 flex-col">
      <CardHeader
        title="Memories"
        subtitle="Strongest first, as evaluated at the last flush"
        actions={
          <Select
            className="h-7 text-xs"
            value={ownerId}
            onChange={(event) => setOwnerId(event.target.value)}
            aria-label="Whose memories to show"
          >
            <option value="">everyone</option>
            {objectIds.map((id) => (
              <option key={id} value={id}>
                {id}
              </option>
            ))}
          </Select>
        }
      />
      <div className="min-h-0 flex-1 overflow-auto">
        {memories.data && memories.data.items.length > 0 ? (
          <table className="w-full text-xs">
            <thead className="sticky top-0 bg-[var(--surface-1)] text-[var(--text-muted)]">
              <tr className="border-b border-[var(--border)]">
                <th className="px-3 py-1.5 text-left font-medium">holder</th>
                <th className="px-3 py-1.5 text-left font-medium">about</th>
                <th className="px-3 py-1.5 text-left font-medium">kind</th>
                <th className="px-3 py-1.5 text-right font-medium">strength</th>
                <th className="px-3 py-1.5 text-right font-medium">feeling</th>
                <th className="px-3 py-1.5 text-right font-medium">recalled</th>
              </tr>
            </thead>
            <tbody>
              {memories.data.items.map((memory: MemoryView) => (
                <tr key={memory.id} className="border-b border-[var(--border)]/60">
                  <td className="px-3 py-1.5">{memory.ownerId}</td>
                  <td className="px-3 py-1.5">{memory.subjectId}</td>
                  <td className="px-3 py-1.5 text-[var(--text-muted)]">{memory.kind}</td>
                  <td className="tabular px-3 py-1.5 text-right">
                    <StrengthBar value={memory.currentStrength} />
                  </td>
                  <td
                    className={cn(
                      "tabular px-3 py-1.5 text-right",
                      memory.valence < -0.05 && "text-[var(--diverging-negative)]",
                      memory.valence > 0.05 && "text-[var(--diverging-positive)]",
                    )}
                  >
                    {formatNumber(memory.valence, 2)}
                  </td>
                  <td className="tabular px-3 py-1.5 text-right text-[var(--text-muted)]">
                    {memory.reactivationCount}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <EmptyState
            title="No memories yet"
            description="Memories appear once an event or an interaction gives someone something to remember."
          />
        )}
      </div>
    </Card>
  );
}

/** A strength reading shown as both a bar and a number, so it is legible without colour. */
function StrengthBar({ value }: { value: number }) {
  return (
    <span className="flex items-center justify-end gap-2">
      <span className="h-1.5 w-16 overflow-hidden rounded-full bg-[var(--surface-3)]">
        <span
          className="block h-full rounded-full bg-[var(--accent)]"
          style={{ width: `${Math.max(2, Math.min(100, value * 100))}%` }}
        />
      </span>
      {formatNumber(value, 2)}
    </span>
  );
}

/** How the group currently sees itself. */
export function RelationshipPanel({ runId, theme }: { runId: string; theme: string }) {
  const relationships = useRelationships(runId);

  return (
    <Card>
      <CardHeader
        title="Who thinks what of whom"
        subtitle="Strength-weighted average feeling, derived from stored memories"
      />
      <div className="p-2">
        <RelationshipHeatmap cells={relationships.data ?? []} theme={theme} />
      </div>
    </Card>
  );
}

/**
 * Checkpoints, and the branches taken from them.
 *
 * Forking is offered next to each checkpoint rather than buried in a menu, because branching a run
 * to try a different continuation is the main thing checkpoints are for.
 */
export function CheckpointPanel({
  runId,
  forks,
  onRestored,
}: {
  runId: string;
  forks: RunSummary[];
  onRestored: () => void;
}) {
  const checkpoints = useCheckpoints(runId);
  const restore = useRestore(runId);

  return (
    <Card className="flex min-h-0 flex-col">
      <CardHeader
        title="Checkpoints"
        subtitle="Resume from one, or branch a new run at that tick"
        actions={restore.isPending ? <Spinner /> : null}
      />
      <div className="min-h-0 flex-1 overflow-auto">
        {checkpoints.data && checkpoints.data.length > 0 ? (
          <ul className="divide-y divide-[var(--border)]">
            {checkpoints.data.map((checkpoint: Checkpoint) => (
              <li key={checkpoint.id} className="flex items-center gap-2 px-3 py-2">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 text-xs">
                    <span className="tabular font-medium">tick {formatTick(checkpoint.tick)}</span>
                    {checkpoint.label ? (
                      <span className="truncate text-[var(--text-secondary)]">{checkpoint.label}</span>
                    ) : null}
                    {checkpoint.automatic ? <Badge tone="neutral">auto</Badge> : null}
                  </div>
                  <div className="text-[11px] text-[var(--text-muted)]">
                    {formatBytes(checkpoint.stateBytes)}
                  </div>
                </div>
                <Button
                  size="sm"
                  variant="ghost"
                  title="Rewind this run to that tick"
                  onClick={async () => {
                    await restore.mutateAsync({ checkpointId: checkpoint.id, fork: false });
                    onRestored();
                  }}
                >
                  Rewind
                </Button>
                <Button
                  size="sm"
                  title="Start a separate run branching from that tick"
                  onClick={async () => {
                    await restore.mutateAsync({ checkpointId: checkpoint.id, fork: true });
                    onRestored();
                  }}
                >
                  Fork
                </Button>
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            title="No checkpoints"
            description="Take one before trying something, so you can come back and try something else instead."
          />
        )}

        {forks.length > 0 ? (
          <div className="border-t border-[var(--border)] px-3 py-2">
            <div className="mb-1.5 text-[11px] font-medium uppercase tracking-wide text-[var(--text-muted)]">
              Branches
            </div>
            <ul className="space-y-1">
              {forks.map((fork) => (
                <li key={fork.id} className="text-xs">
                  <Link
                    to="/runs/$runId"
                    params={{ runId: fork.id }}
                    className="text-[var(--accent)] hover:underline"
                  >
                    {fork.name}
                  </Link>
                  <span className="tabular ml-2 text-[var(--text-muted)]">
                    from tick {fork.forkedFromTick} · now {formatTick(fork.tick)}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>
    </Card>
  );
}
