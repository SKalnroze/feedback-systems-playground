import type { SeriesDefinition, SeriesView } from "@/api/types";
import { useSeriesCatalogue, useSeriesData } from "@/api/queries";
import { TimeSeriesChart } from "@/components/charts/TimeSeriesChart";
import { Badge, Button, Card, CardHeader, EmptyState, Select } from "@/components/ui/primitives";
import { cn, seriesColor, seriesLabel } from "@/lib/utils";
import { api } from "@/api/client";
import { PanelBody, panelPhase } from "@/components/ui/PanelBody";
import { useMemo, useState } from "react";

export type ChartPanelConfig = {
  id: string;
  title: string;
  seriesKeys: string[];
};

const RESOLUTIONS = [
  { value: 0, label: "auto" },
  { value: 1, label: "every tick" },
  { value: 10, label: "10 ticks" },
  { value: 50, label: "50 ticks" },
  { value: 250, label: "250 ticks" },
] as const;

/**
 * The chart workspace: several stacked panels over one shared time axis.
 *
 * Panels rather than one chart with everything on it, because variables here have genuinely
 * different scales - a memory count and a trust level do not belong on the same axis - and the
 * honest way to compare them is aligned panels, not a second y-axis.
 */
export function ChartWorkspace({
  runId,
  panels,
  onPanelsChange,
  theme,
  live,
  markers,
  objectTypes,
  onTickClick,
}: {
  runId: string;
  panels: ChartPanelConfig[];
  onPanelsChange: (panels: ChartPanelConfig[]) => void;
  theme: string;
  live: boolean;
  markers: { tick: number; label: string }[];
  /** Object id to object type id, used to offer whole-type selections in the picker. */
  objectTypes: Map<string, string>;
  onTickClick?: (tick: number) => void;
}) {
  const catalogue = useSeriesCatalogue(runId);
  const [resolution, setResolution] = useState(0);
  const [range, setRange] = useState<{ from: number; to: number } | null>(null);
  // Locking the y-axis to 0..1 makes panels comparable at a glance. Off by default because most
  // variables are unit stocks and already sit in that band; on, it stops an auto-scaled panel of a
  // barely-moving series from looking like a dramatic one.
  const [lockAxis, setLockAxis] = useState(false);

  const grouped = useMemo(() => groupSeries(catalogue.data ?? []), [catalogue.data]);

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button
          size="sm"
          onClick={() =>
            onPanelsChange([
              ...panels,
              { id: `panel-${Date.now()}`, title: `Panel ${panels.length + 1}`, seriesKeys: [] },
            ])
          }
        >
          + Add panel
        </Button>

        <label className="flex items-center gap-1.5 text-xs text-[var(--text-muted)]">
          Resolution
          <Select
            className="h-7 text-xs"
            value={resolution}
            onChange={(event) => setResolution(Number(event.target.value))}
          >
            {RESOLUTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </Select>
        </label>

        <label className="flex items-center gap-1.5 text-xs text-[var(--text-muted)]">
          <input type="checkbox" checked={lockAxis} onChange={(event) => setLockAxis(event.target.checked)} />
          Lock y-axis 0–1
        </label>

        <a
          className="rounded-md px-2 py-1 text-xs text-[var(--text-muted)] hover:bg-[var(--surface-2)]"
          href={api.exportSeriesUrl(
            runId,
            panels.flatMap((panel) => panel.seriesKeys),
          )}
          title="Download the charted series as CSV"
        >
          Export CSV
        </a>

        {range ? (
          <Button size="sm" variant="ghost" onClick={() => setRange(null)}>
            Reset zoom ({range.from}–{range.to})
          </Button>
        ) : null}
      </div>

      {panels.map((panel) => (
        <ChartPanel
          key={panel.id}
          runId={runId}
          panel={panel}
          catalogue={grouped}
          theme={theme}
          live={live}
          resolution={resolution}
          lockAxis={lockAxis}
          range={range}
          markers={markers}
          objectTypes={objectTypes}
          onRangeChange={setRange}
          onTickClick={onTickClick}
          onChange={(next) => onPanelsChange(panels.map((item) => (item.id === panel.id ? next : item)))}
          onRemove={() => onPanelsChange(panels.filter((item) => item.id !== panel.id))}
        />
      ))}

      {panels.length === 0 ? (
        <Card>
          <EmptyState
            title="No chart panels"
            description="Add a panel and choose the variables to plot. Panels share one time axis, so zooming any of them lines the others up."
          />
        </Card>
      ) : null}
    </div>
  );
}

function ChartPanel({
  runId,
  panel,
  catalogue,
  theme,
  live,
  resolution,
  lockAxis,
  range,
  markers,
  objectTypes,
  onRangeChange,
  onTickClick,
  onChange,
  onRemove,
}: {
  runId: string;
  panel: ChartPanelConfig;
  catalogue: Map<string, SeriesDefinition[]>;
  theme: string;
  live: boolean;
  resolution: number;
  lockAxis: boolean;
  range: { from: number; to: number } | null;
  markers: { tick: number; label: string }[];
  objectTypes: Map<string, string>;
  onRangeChange: (range: { from: number; to: number } | null) => void;
  onTickClick?: (tick: number) => void;
  onChange: (panel: ChartPanelConfig) => void;
  onRemove: () => void;
}) {
  const [pickerOpen, setPickerOpen] = useState(panel.seriesKeys.length === 0);

  const data = useSeriesData(
    runId,
    panel.seriesKeys,
    { from: range?.from ?? 0, to: range?.to ?? -1, resolution },
    live ? 1500 : false,
  );

  const series: SeriesView[] = data.data?.series ?? [];
  const bucketed = series.some((entry) => entry.bucketed);

  // Three different nothings, which the panel used to report as one. No series picked is a
  // question for the reader; a request in flight is not their problem; a completed request with no
  // points means the range genuinely holds none.
  const nothingPicked = panel.seriesKeys.length === 0;
  const noPoints = series.every((entry) => entry.points.length === 0);
  const phase = panelPhase(data, noPoints, !nothingPicked);

  return (
    <Card className="min-w-0">
      <CardHeader
        title={
          <input
            className="w-full bg-transparent text-sm font-semibold outline-none"
            value={panel.title}
            onChange={(event) => onChange({ ...panel, title: event.target.value })}
            aria-label="Panel title"
          />
        }
        subtitle={
          bucketed
            ? `Averaged into buckets of ${data.data?.resolution} ticks`
            : `${series.length} series · every sampled tick`
        }
        actions={
          <>
            <Button size="sm" variant="ghost" onClick={() => setPickerOpen((open) => !open)}>
              {pickerOpen ? "Hide series" : "Series"}
            </Button>
            <Button size="sm" variant="ghost" onClick={onRemove} title="Remove this panel">
              ✕
            </Button>
          </>
        }
      />

      {pickerOpen ? (
        <SeriesPicker
          catalogue={catalogue}
          selected={panel.seriesKeys}
          objectTypes={objectTypes}
          onToggle={(key) =>
            onChange({
              ...panel,
              seriesKeys: panel.seriesKeys.includes(key)
                ? panel.seriesKeys.filter((item) => item !== key)
                : [...panel.seriesKeys, key],
            })
          }
          onSetMany={(keys, selectedNow) =>
            onChange({
              ...panel,
              seriesKeys: selectedNow
                ? // Order preserved and duplicates dropped: adding a whole type twice must not
                  // plot anything twice.
                  [...panel.seriesKeys, ...keys.filter((key) => !panel.seriesKeys.includes(key))]
                : panel.seriesKeys.filter((key) => !keys.includes(key)),
            })
          }
        />
      ) : null}

      <div className="px-2 pb-2">
        {nothingPicked ? (
          <div
            className="flex items-center justify-center text-xs text-[var(--text-muted)]"
            style={{ height: 260 }}
          >
            Pick one or more series to chart.
          </div>
        ) : (
        <PanelBody
          phase={phase}
          error={data.error}
          height={260}
          emptyTitle="No samples in this range"
          emptyDescription="The run has not reached these ticks, or the series was not sampled here."
        >
        <TimeSeriesChart
          series={series}
          theme={theme}
          markers={markers}
          range={range}
          yMin={lockAxis ? 0 : undefined}
          yMax={lockAxis ? 1 : undefined}
          onRangeChange={onRangeChange}
          onTickClick={onTickClick}
        />
        </PanelBody>
        )}
      </div>
    </Card>
  );
}

/**
 * Series picker, grouped by what the series is about.
 *
 * A run of eight people produces around fifty series; an ungrouped list of them is unusable.
 */
function SeriesPicker({
  catalogue,
  selected,
  objectTypes,
  onToggle,
  onSetMany,
}: {
  catalogue: Map<string, SeriesDefinition[]>;
  selected: string[];
  objectTypes: Map<string, string>;
  onToggle: (key: string) => void;
  onSetMany: (keys: string[], selected: boolean) => void;
}) {
  // One row per object type, listing the variables its objects share. Selecting "trust" here means
  // "every person's trust", which is nearly always what a reader wants and was previously eight
  // separate clicks that had to be repeated whenever an object was added.
  const byType = useMemo(() => {
    const types = new Map<string, Map<string, string[]>>();
    for (const entries of catalogue.values()) {
      for (const entry of entries) {
        const typeId = entry.objectId ? objectTypes.get(entry.objectId) : undefined;
        if (!typeId || !entry.variable) continue;
        const variables = types.get(typeId) ?? new Map<string, string[]>();
        const keys = variables.get(entry.variable) ?? [];
        keys.push(entry.seriesKey);
        variables.set(entry.variable, keys);
        types.set(typeId, variables);
      }
    }
    return new Map(Array.from(types.entries()).sort(([a], [b]) => a.localeCompare(b)));
  }, [catalogue, objectTypes]);
  if (catalogue.size === 0) {
    return (
      <p className="px-4 py-3 text-xs text-[var(--text-muted)]">
        No series recorded yet — step the run a few ticks.
      </p>
    );
  }

  return (
    <div className="max-h-56 overflow-auto border-b border-[var(--border)] px-4 py-3">
      {Array.from(byType.entries()).map(([typeId, variables]) => (
        <div key={`type-${typeId}`} className="mb-3">
          <div className="mb-1.5 text-[11px] font-medium uppercase tracking-wide text-[var(--text-muted)]">
            all {typeId}
          </div>
          <div className="flex flex-wrap gap-1.5">
            {Array.from(variables.entries())
              .sort(([a], [b]) => a.localeCompare(b))
              .map(([variable, keys]) => {
                // Selected only when every object of the type is plotted: a partial selection has
                // to look different from a complete one, or clicking it is a guess.
                const chosen = keys.filter((key) => selected.includes(key)).length;
                const all = chosen === keys.length;
                const some = chosen > 0 && !all;
                return (
                  <button
                    key={`${typeId}.${variable}`}
                    type="button"
                    title={`${all ? "Remove" : "Plot"} ${variable} for all ${keys.length} ${typeId} objects`}
                    onClick={() => onSetMany(keys, !all)}
                    className={cn(
                      "inline-flex items-center gap-1.5 rounded border px-2 py-1 text-xs transition-colors",
                      all
                        ? "border-[var(--accent)] bg-[var(--accent)]/10 text-[var(--text-primary)]"
                        : some
                          ? "border-[var(--accent)]/50 text-[var(--text-secondary)] hover:bg-[var(--surface-2)]"
                          : "border-[var(--border)] text-[var(--text-secondary)] hover:bg-[var(--surface-2)]",
                    )}
                  >
                    <span
                      aria-hidden
                      className={cn("size-2 rounded-sm", some && "opacity-50")}
                      style={{ background: chosen > 0 ? "var(--accent)" : "var(--border-strong)" }}
                    />
                    {variable}
                    <span className="text-[10px] text-[var(--text-muted)]">
                      {chosen}/{keys.length}
                    </span>
                  </button>
                );
              })}
          </div>
        </div>
      ))}

      {Array.from(catalogue.entries()).map(([group, entries]) => (
        <div key={group} className="mb-3 last:mb-0">
          <div className="mb-1.5 text-[11px] font-medium uppercase tracking-wide text-[var(--text-muted)]">
            {group}
          </div>
          <div className="flex flex-wrap gap-1.5">
            {entries.map((entry) => {
              const active = selected.includes(entry.seriesKey);
              return (
                <button
                  key={entry.seriesKey}
                  type="button"
                  onClick={() => onToggle(entry.seriesKey)}
                  className={cn(
                    "inline-flex items-center gap-1.5 rounded border px-2 py-1 text-xs transition-colors",
                    active
                      ? "border-[var(--accent)] bg-[var(--accent)]/10 text-[var(--text-primary)]"
                      : "border-[var(--border)] text-[var(--text-secondary)] hover:bg-[var(--surface-2)]",
                  )}
                >
                  <span
                    aria-hidden
                    className="size-2 rounded-sm"
                    style={{ background: active ? seriesColor(entry.seriesKey) : "var(--border-strong)" }}
                  />
                  {seriesLabel(entry.seriesKey)}
                </button>
              );
            })}
          </div>
        </div>
      ))}
      {selected.length > 8 ? (
        <Badge tone="warning">
          {selected.length} series — beyond eight, colours repeat and the chart gets hard to read
        </Badge>
      ) : null}
    </div>
  );
}

function groupSeries(entries: SeriesDefinition[]): Map<string, SeriesDefinition[]> {
  const groups = new Map<string, SeriesDefinition[]>();
  for (const entry of entries) {
    const group = entry.objectId ?? (entry.category === "global" ? "global" : "system");
    const existing = groups.get(group);
    if (existing) existing.push(entry);
    else groups.set(group, [entry]);
  }
  return new Map(Array.from(groups.entries()).sort(([a], [b]) => a.localeCompare(b)));
}
