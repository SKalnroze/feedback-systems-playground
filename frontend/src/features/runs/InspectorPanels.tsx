import type { Checkpoint, LogEntry, MemoryView, RunSummary } from "@/api/types";
import {
  useCheckpoints,
  useDescribeCheckpoint,
  useMemories,
  useRelationships,
  useRestore,
  useRunLog,
} from "@/api/queries";
import { api } from "@/api/client";
import { RelationshipHeatmap } from "@/components/charts/RelationshipHeatmap";
import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  ErrorNote,
  Input,
  Skeleton,
  Select,
  Spinner,
} from "@/components/ui/primitives";
import { ConfirmButton } from "@/components/ui/ConfirmButton";
import { EditableDescription } from "@/components/ui/EditableDescription";
import { PanelBody, panelPhase } from "@/components/ui/PanelBody";
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
  const [search, setSearch] = useState("");
  // A wider page is fetched when searching: filtering client-side over the default 200 would
  // silently answer "nothing matched" when the match was simply further back.
  const log = useRunLog(runId, type || undefined, search ? 2000 : 200, live ? 2000 : false);

  const needle = search.trim().toLowerCase();
  const entries = (log.data?.items ?? []).filter(
    (entry: LogEntry) =>
      needle === "" ||
      entry.detail.toLowerCase().includes(needle) ||
      (entry.subject ?? "").toLowerCase().includes(needle),
  );

  return (
    <Card className="flex min-h-0 flex-col">
      <CardHeader
        title="What happened"
        subtitle="Newest first"
        actions={
          <>
            {log.isFetching ? <Spinner /> : null}
            <Input
              className="h-7 w-32 text-xs"
              placeholder="Search…"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              aria-label="Search the log"
            />
            <a
              className="rounded-md px-2 py-1 text-xs text-[var(--text-muted)] hover:bg-[var(--surface-2)]"
              href={api.exportLogUrl(runId, type || undefined)}
              title="Download this log as CSV"
            >
              CSV
            </a>
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
        {panelPhase(log, entries.length === 0) !== "ready" ? (
          <PanelBody
            phase={panelPhase(log, entries.length === 0)}
            error={log.error}
            skeletonRows={6}
            emptyTitle={needle ? "Nothing matches that search" : "Nothing logged yet"}
            emptyDescription={
              needle ? "Try a shorter search, or a different entry type." : "Start the run to see what it does."
            }
          >
            {null}
          </PanelBody>
        ) : entries.length > 0 ? (
          <ul className="divide-y divide-[var(--border)] text-xs">
            {entries.map((entry: LogEntry) => (
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
  const [sort, setSort] = useState<"strength" | "age" | "recalls" | "feeling">("strength");
  const memories = useMemories(runId, ownerId || undefined);

  // Sorted here rather than in SQL: the page is already capped, and switching the ordering is the
  // sort of thing a reader does repeatedly while looking at one set of memories.
  const rows = [...(memories.data?.items ?? [])].sort((a, b) => {
    switch (sort) {
      case "age":
        return a.createdTick - b.createdTick;
      case "recalls":
        return b.reactivationCount - a.reactivationCount;
      case "feeling":
        return a.valence - b.valence;
      default:
        return b.currentStrength - a.currentStrength;
    }
  });

  /**
   * Current strength against what it was first laid down at.
   *
   * Deliberately not called retention: reactivation can push a trace above its original strength,
   * so this reads over 100% for anything that has been recalled and consolidated. That is the
   * spacing effect showing up in a table, and clamping it to look like a percentage of something
   * retained would hide the most interesting rows.
   */
  function versusInitial(memory: MemoryView): number {
    return memory.initialStrength <= 0 ? 0 : memory.currentStrength / memory.initialStrength;
  }

  return (
    <Card className="flex min-h-0 flex-col">
      <CardHeader
        title="Memories"
        subtitle="As evaluated at the last flush"
        actions={
          <>
          <Select
            className="h-7 text-xs"
            value={sort}
            onChange={(event) => setSort(event.target.value as typeof sort)}
            aria-label="Sort memories"
          >
            <option value="strength">strongest</option>
            <option value="age">oldest</option>
            <option value="recalls">most recalled</option>
            <option value="feeling">most negative</option>
          </Select>
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
          </>
        }
      />
      <div className="min-h-0 flex-1 overflow-auto">
        {/* A loading fetch must not render the empty state: "no memories yet" is a claim about the
            run, and stating it while the answer is still in flight is a confident wrong answer. */}
        {panelPhase(memories, rows.length === 0) !== "ready" ? (
          <PanelBody
            phase={panelPhase(memories, rows.length === 0)}
            error={memories.error}
            skeletonRows={6}
            emptyTitle="No memories yet"
            emptyDescription="Memories appear once an event or an interaction gives someone something to remember."
          >
            {null}
          </PanelBody>
        ) : rows.length > 0 ? (
          <table className="w-full text-xs">
            <thead className="sticky top-0 bg-[var(--surface-1)] text-[var(--text-muted)]">
              <tr className="border-b border-[var(--border)]">
                <th className="px-3 py-1.5 text-left font-medium">holder</th>
                <th className="px-3 py-1.5 text-left font-medium">about</th>
                <th className="px-3 py-1.5 text-left font-medium">kind</th>
                <th className="px-3 py-1.5 text-right font-medium">strength</th>
                <th className="px-3 py-1.5 text-right font-medium">feeling</th>
                <th className="px-3 py-1.5 text-right font-medium" title="Current strength against the strength it was first laid down at; over 100% means recall has strengthened it">vs first</th>
                <th className="px-3 py-1.5 text-right font-medium">recalled</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((memory: MemoryView) => (
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
                  <td
                    className="tabular px-3 py-1.5 text-right text-[var(--text-muted)]"
                    title={`laid down at tick ${memory.createdTick}`}
                  >
                    {Math.round(versusInitial(memory) * 100)}%
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
        <PanelBody
          phase={panelPhase(relationships, (relationships.data ?? []).length === 0)}
          error={relationships.error}
          height={280}
          emptyTitle="Nobody remembers anything yet"
          emptyDescription="The matrix fills in once interactions or events have given people something to remember."
        >
          <RelationshipHeatmap cells={relationships.data ?? []} theme={theme} />
        </PanelBody>
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
  const describe = useDescribeCheckpoint(runId);
  const [actionError, setActionError] = useState<string | null>(null);

  return (
    <Card className="flex min-h-0 flex-col">
      <CardHeader
        title="Checkpoints"
        subtitle="Resume from one, or branch a new run at that tick"
        actions={restore.isPending ? <Spinner /> : null}
      />
      {actionError ? (
        <div className="px-3 pb-2">
          <ErrorNote message={actionError} />
        </div>
      ) : null}
      <div className="min-h-0 flex-1 overflow-auto">
        {panelPhase(checkpoints, (checkpoints.data ?? []).length === 0) === "loading" ? (
          <Skeleton rows={3} />
        ) : checkpoints.data && checkpoints.data.length > 0 ? (
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
                  <EditableDescription
                    value={checkpoint.description}
                    placeholder="Why keep this tick?"
                    rows={1}
                    onSave={(description) =>
                      describe.mutate({ checkpointId: checkpoint.id, description })
                    }
                  />
                </div>
                <ConfirmButton
                  confirmLabel="Discard later history?"
                  title="Rewind this run to that tick, discarding everything after it"
                  onConfirm={() => {
                    setActionError(null);
                    restore.mutate(
                      { checkpointId: checkpoint.id, fork: false },
                      {
                        onSuccess: onRestored,
                        onError: (cause) =>
                          setActionError(cause instanceof Error ? cause.message : "Could not rewind the run."),
                      },
                    );
                  }}
                >
                  Rewind
                </ConfirmButton>
                <Button
                  size="sm"
                  title="Start a separate run branching from that tick"
                  onClick={() => {
                    setActionError(null);
                    restore.mutate(
                      { checkpointId: checkpoint.id, fork: true },
                      {
                        onSuccess: onRestored,
                        onError: (cause) =>
                          setActionError(cause instanceof Error ? cause.message : "Could not fork the run."),
                      },
                    );
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
