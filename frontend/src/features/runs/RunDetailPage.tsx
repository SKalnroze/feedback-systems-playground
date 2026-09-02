import { useCheckpoint, useForks, useRun,
  useDescribeRun,
  useRunLog,
} from "@/api/queries";
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
import { EditableDescription } from "@/components/ui/EditableDescription";
import { useChartWorkspace } from "@/features/runs/useChartWorkspace";
import { DistributionPanel } from "@/features/runs/DistributionPanel";
import { useTheme } from "@/lib/theme";
import { useValueFormat } from "@/lib/valueFormat";
import { cn, formatTick } from "@/lib/utils";
import { Link, useParams } from "@tanstack/react-router";
import { useEffect, useMemo, useState } from "react";

type Tab = "charts" | "distribution" | "relationships" | "memories";

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
  const describe = useDescribeRun(runId);
  const forks = useForks(runId);
  const checkpoint = useCheckpoint(runId);
  const live = useLiveSnapshot(runId);

  useRunStream(runId);

  const [tab, setTab] = useState<Tab>("charts");
  const [cursorTick, setCursorTick] = useState<number | null>(null);
  const [hoverTick, setHoverTick] = useState<number | null>(null);
  // The selected tick window lives here rather than in the charts, because once it can also narrow
  // the log and the memory table it is a statement about what part of the run is being read.
  const [range, setRange] = useState<{ from: number; to: number } | null>(null);
  const [crossFilter, setCrossFilter] = useState(false);
  const { format, update: setFormat } = useValueFormat();
  const workspace = useChartWorkspace(runId);
  const panels = workspace.state.panels;
  const setPanels = (next: ChartPanelConfig[]) => workspace.update({ panels: next });

  // Events, checkpoints and notes from the log, drawn on every panel's time axis. Capped and
  // fetched once: this is a backdrop for the charts, not the log panel, and a run with thousands of
  // interactions would otherwise draw thousands of rules.
  const eventLog = useRunLog(runId, "EVENT", 200, false);
  // Notes rather than checkpoints: automatic checkpoints land every couple of hundred ticks and
  // marking them all says nothing about the run, while the Checkpoints panel already lists them.
  const noteLog = useRunLog(runId, "NOTE", 50, false);
  const chartEvents = useMemo(
    () =>
      [...(eventLog.data?.items ?? []), ...(noteLog.data?.items ?? [])].map((entry) => ({
        tick: entry.tick,
        type: entry.type,
        detail: entry.detail,
      })),
    [eventLog.data, noteLog.data],
  );

  const objectIds = useMemo(
    () => (run.data?.spec?.objects ?? []).map((object) => object.id),
    [run.data?.spec],
  );

  // Label and size of each authored object, so a series key can be said in words rather than left
  // as "staff.morale.mean" for the reader to decode.
  const groups = useMemo(() => {
    const byId = new Map<string, { label: string; count: number }>();
    for (const object of run.data?.spec?.objects ?? []) {
      byId.set(object.id, { label: object.label || object.id, count: object.count });
    }
    return byId;
  }, [run.data?.spec]);

  // Which type each object is, so the series picker can offer "every learner's motivation" rather
  // than making the reader tick eight boxes that mean one thing.
  const objectTypes = useMemo(() => {
    const byObject = new Map<string, string>();
    for (const object of run.data?.spec?.objects ?? []) {
      byObject.set(object.id, object.typeId);
    }
    return byObject;
  }, [run.data?.spec]);

  // A first panel is seeded from the spec so the page is useful the moment it opens, rather than
  // asking the user to configure a chart before seeing anything at all.
  useEffect(() => {
    if (panels.length > 0 || !run.data?.spec) return;
    const spec = run.data.spec;
    const firstVariable = spec.objectTypes[0]?.variables[0]?.name;
    if (!firstVariable) return;
    // A group is recorded as statistics, not as one series per member, so its keys carry a
    // statistic suffix. Seeding the plain key gave every population run an empty opening chart,
    // which reads as a broken tool rather than as a naming mismatch.
    const keys = spec.objects.slice(0, 6).flatMap((object) =>
      object.count > 1
        ? [
            `${object.id}.${firstVariable}.mean`,
            `${object.id}.${firstVariable}.min`,
            `${object.id}.${firstVariable}.max`,
          ]
        : [`${object.id}.${firstVariable}`],
    );
    setPanels([{ id: "panel-1", title: firstVariable, seriesKeys: keys }]);
  }, [run.data?.spec, panels.length]);

  const status = live?.status ?? run.data?.summary.status;
  const isLive = status === "RUNNING";

  // The clicked tick stays put; the hovered one follows the pointer through the log, so reading an
  // entry shows immediately where on the chart it happened.
  const markers = useMemo(() => {
    const marks: { tick: number; label: string }[] = [];
    if (cursorTick !== null) marks.push({ tick: cursorTick, label: `tick ${formatTick(cursorTick)}` });
    if (hoverTick !== null && hoverTick !== cursorTick) marks.push({ tick: hoverTick, label: "" });
    return marks;
  }, [cursorTick, hoverTick]);

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

      <div className="max-w-3xl px-4 pt-1">
        <EditableDescription
          value={summary.description}
          placeholder="What is this run for? What should someone look at?"
          onSave={(description) => describe.mutate(description)}
        />
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
          {/* Wraps rather than overflowing: this row now carries the formatting controls as well as
              the tabs, which is enough to outgrow a narrow window. */}
          <div className="flex flex-wrap items-center gap-1">
            {(["charts", "distribution", "relationships", "memories"] as const).map((value) => (
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
            <span className="ml-auto flex items-center gap-3 text-[11px] text-[var(--text-muted)]">
              <label className="flex items-center gap-1" title="Decimal places, used everywhere a value is written">
                decimals
                <select
                  className="rounded border border-[var(--border)] bg-transparent px-1 py-0.5 text-[11px]"
                  value={format.decimals}
                  onChange={(event) => setFormat({ decimals: Number(event.target.value) })}
                >
                  {[0, 1, 2, 3, 4].map((places) => (
                    <option key={places} value={places}>
                      {places}
                    </option>
                  ))}
                </select>
              </label>
              <label
                className="flex items-center gap-1"
                title="Write values between -1 and 1 as percentages. Values outside that range are left alone."
              >
                <input
                  type="checkbox"
                  checked={format.percent}
                  onChange={(event) => setFormat({ percent: event.target.checked })}
                />
                %
              </label>
              <span>
                {formatTick(run.data.sampleCount)} samples · {formatTick(run.data.logEntryCount)} log entries
              </span>
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
                objectTypes={objectTypes}
                groups={groups}
                events={chartEvents}
                view={workspace.state}
                onViewChange={workspace.update}
                onTickClick={setCursorTick}
                range={range}
                onRangeChange={setRange}
                format={format}
              />
            ) : null}
            {tab === "distribution" ? (
              <DistributionPanel
                runId={runId}
                spec={run.data?.spec ?? null}
                live={isLive}
                theme={theme}
                tick={summary.tick}
                format={format}
              />
            ) : null}
            {tab === "relationships" ? <RelationshipPanel runId={runId} theme={theme} /> : null}
            {tab === "memories" ? (
              <MemoryPanel
                runId={runId}
                objectIds={objectIds}
                spec={run.data?.spec ?? null}
                tick={summary.tick}
                format={format}
                tickRange={crossFilter ? range : null}
              />
            ) : null}
          </div>
        </div>

        <div className="grid min-h-0 grid-rows-2 gap-3">
          <EventLogPanel
                runId={runId}
                live={isLive}
                onTickSelect={setCursorTick}
                onTickHover={setHoverTick}
                tickRange={crossFilter ? range : null}
              />
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

      {cursorTick !== null || range !== null ? (
        <Card className="mx-3 mb-3 flex flex-wrap items-center gap-3 px-3 py-2 text-xs">
          {cursorTick !== null ? (
            <>
              <span className="text-[var(--text-muted)]">
                Marker at tick <span className="tabular">{formatTick(cursorTick)}</span>
              </span>
              <Button size="sm" variant="ghost" onClick={() => setCursorTick(null)}>
                Clear marker
              </Button>
            </>
          ) : null}

          {range !== null ? (
            <>
              <span className="text-[var(--text-muted)]">
                Ticks <span className="tabular">{formatTick(Math.round(range.from))}</span>–
                <span className="tabular">{formatTick(Math.round(range.to))}</span> selected
              </span>
              {/* Offered rather than applied: narrowing a chart is about looking closely, and
                  silently hiding log entries outside the window would make the run look like it
                  did less than it did. */}
              <label
                className="flex items-center gap-1.5 text-[var(--text-secondary)]"
                title="Show only the log entries and memories laid down inside the selected ticks"
              >
                <input
                  type="checkbox"
                  checked={crossFilter}
                  onChange={(event) => setCrossFilter(event.target.checked)}
                />
                Filter the log and memories to this window
              </label>
              <Button size="sm" variant="ghost" onClick={() => setRange(null)}>
                Clear selection
              </Button>
            </>
          ) : null}
        </Card>
      ) : null}
    </div>
  );
}
