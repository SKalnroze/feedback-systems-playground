import { useCheckpoint, useForks, useRun } from "@/api/queries";
import { useLiveSnapshot, useRunStream } from "@/api/liveRun";
import { Badge, Button, Card, ErrorNote, Spinner } from "@/components/ui/primitives";
import { ChartWorkspace, type ChartPanelConfig } from "@/features/runs/ChartWorkspace";
import { ControlBar } from "@/features/runs/ControlBar";
import {
  CheckpointPanel,
  EventLogPanel,
  MemoryPanel,
  RelationshipPanel,
} from "@/features/runs/InspectorPanels";
import { useTheme } from "@/lib/theme";
import { cn, formatTick } from "@/lib/utils";
import { Link, useParams } from "@tanstack/react-router";
import { useEffect, useMemo, useState } from "react";

type Tab = "charts" | "relationships" | "memories";

/**
 * The run dashboard.
 *
 * Layout follows how the tool is actually used: transport controls pinned at the top because they
 * are touched constantly, charts taking the space because they are what is being read, and the log
 * beside them because the first question about any movement in a chart is what caused it.
 */
export function RunDetailPage() {
  const { runId } = useParams({ from: "/runs/$runId" });
  const { theme } = useTheme();
  const run = useRun(runId);
  const forks = useForks(runId);
  const checkpoint = useCheckpoint(runId);
  const live = useLiveSnapshot(runId);

  useRunStream(runId);

  const [tab, setTab] = useState<Tab>("charts");
  const [cursorTick, setCursorTick] = useState<number | null>(null);
  const [panels, setPanels] = useState<ChartPanelConfig[]>([]);

  const objectIds = useMemo(
    () => (run.data?.spec?.objects ?? []).map((object) => object.id),
    [run.data?.spec],
  );

  // A first panel is seeded from the spec so the page is useful the moment it opens, rather than
  // asking the user to configure a chart before seeing anything at all.
  useEffect(() => {
    if (panels.length > 0 || !run.data?.spec) return;
    const spec = run.data.spec;
    const firstVariable = spec.objectTypes[0]?.variables[0]?.name;
    if (!firstVariable) return;
    const keys = spec.objects.slice(0, 6).map((object) => `${object.id}.${firstVariable}`);
    setPanels([{ id: "panel-1", title: firstVariable, seriesKeys: keys }]);
  }, [run.data?.spec, panels.length]);

  const status = live?.status ?? run.data?.summary.status;
  const isLive = status === "RUNNING";

  const markers = useMemo(
    () => (cursorTick === null ? [] : [{ tick: cursorTick, label: `tick ${formatTick(cursorTick)}` }]),
    [cursorTick],
  );

  if (run.isLoading) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-sm text-[var(--text-muted)]">
        <Spinner /> Loading run…
      </div>
    );
  }

  if (run.isError || !run.data) {
    return (
      <div className="p-6">
        <ErrorNote message="That run could not be loaded. It may have been deleted." />
        <Link to="/runs" className="mt-3 inline-block text-sm text-[var(--accent)] hover:underline">
          Back to runs
        </Link>
      </div>
    );
  }

  const summary = run.data.summary;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex items-center gap-3 px-4 pt-3">
        <Link to="/runs" className="text-xs text-[var(--text-muted)] hover:underline">
          ← Runs
        </Link>
        <h1 className="truncate text-sm font-semibold">{summary.name}</h1>
        <span className="truncate text-xs text-[var(--text-muted)]">
          {summary.systemName} · seed <span className="tabular">{summary.seed}</span>
        </span>
        {summary.parentRunId ? (
          <Link to="/runs/$runId" params={{ runId: summary.parentRunId }}>
            <Badge tone="neutral">forked from tick {summary.forkedFromTick}</Badge>
          </Link>
        ) : null}
      </div>

      <div className="mt-2">
        <ControlBar run={run.data} onCheckpoint={() => checkpoint.mutate(null)} />
      </div>

      {summary.error ? (
        <div className="px-4 pt-3">
          <ErrorNote message={summary.error} />
        </div>
      ) : null}

      <div className="grid min-h-0 flex-1 gap-3 p-3 xl:grid-cols-[1fr_360px]">
        <div className="flex min-h-0 min-w-0 flex-col gap-3">
          <div className="flex items-center gap-1">
            {(["charts", "relationships", "memories"] as const).map((value) => (
              <button
                key={value}
                type="button"
                onClick={() => setTab(value)}
                className={cn(
                  "rounded-md px-2.5 py-1 text-xs capitalize transition-colors",
                  tab === value
                    ? "bg-[var(--surface-2)] text-[var(--text-primary)]"
                    : "text-[var(--text-secondary)] hover:bg-[var(--surface-2)]",
                )}
              >
                {value}
              </button>
            ))}
            <span className="ml-auto text-[11px] text-[var(--text-muted)]">
              {formatTick(run.data.sampleCount)} samples · {formatTick(run.data.logEntryCount)} log entries
            </span>
          </div>

          <div className="min-h-0 flex-1 overflow-auto">
            {tab === "charts" ? (
              <ChartWorkspace
                runId={runId}
                panels={panels}
                onPanelsChange={setPanels}
                theme={theme}
                live={isLive}
                markers={markers}
                onTickClick={setCursorTick}
              />
            ) : null}
            {tab === "relationships" ? <RelationshipPanel runId={runId} theme={theme} /> : null}
            {tab === "memories" ? <MemoryPanel runId={runId} objectIds={objectIds} /> : null}
          </div>
        </div>

        <div className="grid min-h-0 grid-rows-2 gap-3">
          <EventLogPanel runId={runId} live={isLive} onTickSelect={setCursorTick} />
          <CheckpointPanel
            runId={runId}
            forks={forks.data ?? []}
            onRestored={() => {
              void run.refetch();
              void forks.refetch();
            }}
          />
        </div>
      </div>

      {cursorTick !== null ? (
        <Card className="mx-3 mb-3 flex items-center gap-3 px-3 py-2 text-xs">
          <span className="text-[var(--text-muted)]">
            Marker at tick <span className="tabular">{formatTick(cursorTick)}</span>
          </span>
          <Button size="sm" variant="ghost" onClick={() => setCursorTick(null)}>
            Clear
          </Button>
        </Card>
      ) : null}
    </div>
  );
}
