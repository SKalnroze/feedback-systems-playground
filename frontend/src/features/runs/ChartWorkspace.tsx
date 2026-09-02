import type { SeriesDefinition, SeriesView } from "@/api/types";
import { useSeriesCatalogue, useSeriesData } from "@/api/queries";
import { TimeSeriesChart } from "@/components/charts/TimeSeriesChart";
import { Badge, Button, Card, CardHeader, EmptyState, Select } from "@/components/ui/primitives";
import { cn, describeSeriesKey, seriesColor } from "@/lib/utils";
import { api } from "@/api/client";
import type { ChartWorkspaceState } from "@/features/runs/useChartWorkspace";
import {
  extractBands,
  presentSeries,
  TRANSFORM_AXIS,
  TRANSFORM_HINT,
  TRANSFORM_LABEL,
  type SeriesTransform,
} from "@/features/runs/seriesTransforms";
import { PanelBody, panelPhase } from "@/components/ui/PanelBody";
import type { ValueFormat } from "@/lib/valueFormat";
import { useMemo, useState } from "react";

export type ChartPanelConfig = {
  id: string;
  title: string;
  seriesKeys: string[];
  /**
   * Series that stay chosen but are not drawn.
   *
   * Separate from `seriesKeys` on purpose: deselecting a line to see past it loses its colour and
   * its place in the panel, so putting it back is never quite putting it back. Hidden series are
   * still fetched, which is what makes showing one again instant.
   */
  hidden?: string[];
  /** Colours the reader picked, by series key. */
  colours?: Record<string, string>;
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
  groups,
  events,
  view,
  onViewChange,
  onTickClick,
  range,
  onRangeChange,
  format,
}: {
  runId: string;
  panels: ChartPanelConfig[];
  onPanelsChange: (panels: ChartPanelConfig[]) => void;
  theme: string;
  live: boolean;
  markers: { tick: number; label: string }[];
  /** Object id to object type id, used to offer whole-type selections in the picker. */
  objectTypes: Map<string, string>;
  /** Object id to its label and instance count, for saying series keys in words. */
  groups: Map<string, { label: string; count: number }>;
  /** Events from the run log, drawn on every panel's time axis. */
  events: { tick: number; type: string; detail: string }[];
  /** Persisted view settings, owned by the page so they survive a reload. */
  view: ChartWorkspaceState;
  onViewChange: (change: Partial<ChartWorkspaceState>) => void;
  onTickClick?: (tick: number) => void;
  /**
   * The selected tick window, owned by the page rather than by this component: once a range can
   * filter the log and the memory table too, it stops being a property of the charts.
   */
  range: { from: number; to: number } | null;
  onRangeChange: (range: { from: number; to: number } | null) => void;
  format: ValueFormat;
}) {
  const catalogue = useSeriesCatalogue(runId);
  const { resolution, transform, smoothing, lockAxis, columns, panelHeight } = view;

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
            onChange={(event) => onViewChange({ resolution: Number(event.target.value) })}
          >
            {RESOLUTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </Select>
        </label>

        <label className="flex items-center gap-1.5 text-xs text-[var(--text-muted)]">
          Show
          <Select
            className="h-7 text-xs"
            value={transform}
            title={TRANSFORM_HINT[transform]}
            onChange={(event) => onViewChange({ transform: event.target.value as SeriesTransform })}
          >
            {(Object.keys(TRANSFORM_LABEL) as SeriesTransform[]).map((option) => (
              <option key={option} value={option}>
                {TRANSFORM_LABEL[option]}
              </option>
            ))}
          </Select>
        </label>

        <label className="flex items-center gap-1.5 text-xs text-[var(--text-muted)]">
          Smoothing
          <Select
            className="h-7 text-xs"
            value={smoothing}
            title="A centred moving average. The raw line stays visible underneath."
            onChange={(event) => onViewChange({ smoothing: Number(event.target.value) })}
          >
            {[1, 5, 15, 45].map((window) => (
              <option key={window} value={window}>
                {window === 1 ? "off" : `${window} ticks`}
              </option>
            ))}
          </Select>
        </label>

        <label className="flex items-center gap-1.5 text-xs text-[var(--text-muted)]">
          Layout
          <Select
            className="h-7 text-xs"
            value={columns}
            onChange={(event) => onViewChange({ columns: Number(event.target.value) as 1 | 2 })}
          >
            <option value={1}>one column</option>
            <option value={2}>two columns</option>
          </Select>
        </label>

        <label className="flex items-center gap-1.5 text-xs text-[var(--text-muted)]">
          Height
          <Select
            className="h-7 text-xs"
            value={panelHeight}
            onChange={(event) => onViewChange({ panelHeight: Number(event.target.value) })}
          >
            {[180, 260, 380, 520].map((height) => (
              <option key={height} value={height}>
                {height}px
              </option>
            ))}
          </Select>
        </label>

        <label className="flex items-center gap-1.5 text-xs text-[var(--text-muted)]">
          <input
            type="checkbox"
            checked={lockAxis}
            onChange={(event) => onViewChange({ lockAxis: event.target.checked })}
          />
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
          <Button size="sm" variant="ghost" onClick={() => onRangeChange(null)}>
            Reset zoom ({range.from}–{range.to})
          </Button>
        ) : null}
      </div>

      <div className={cn("grid gap-3", columns === 2 ? "lg:grid-cols-2" : "grid-cols-1")}>
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
          events={events}
          transform={transform}
          smoothing={smoothing}
          panelHeight={panelHeight}
          objectTypes={objectTypes}
          groups={groups}
          format={format}
          onRangeChange={onRangeChange}
          onTickClick={onTickClick}
          onChange={(next) => onPanelsChange(panels.map((item) => (item.id === panel.id ? next : item)))}
          onRemove={() => onPanelsChange(panels.filter((item) => item.id !== panel.id))}
        />
      ))}
      </div>

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
  events,
  transform,
  smoothing,
  panelHeight,
  objectTypes,
  groups,
  format,
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
  events: { tick: number; type: string; detail: string }[];
  transform: SeriesTransform;
  smoothing: number;
  panelHeight: number;
  objectTypes: Map<string, string>;
  groups: Map<string, { label: string; count: number }>;
  format: ValueFormat;
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

  const hidden = panel.hidden ?? [];
  const fetched: SeriesView[] = data.data?.series ?? [];
  // Hidden series are dropped here, before presentation, so a group whose mean is hidden simply
  // stops being a band rather than becoming a band with a hole where its middle line was.
  const raw = fetched.filter((entry) => !hidden.includes(entry.key));
  const bucketed = fetched.some((entry) => entry.bucketed);

  // Presented, then split: transforms and smoothing apply to every series alike, and only then are
  // a group's mean/min/max recognised and folded into one band. Doing it the other way round would
  // normalise the three edges of a band against different ranges and tear it apart.
  const presented = presentSeries(raw, transform, smoothing);
  const { bands, rest: series } = extractBands(presented);

  // What the axis measures. When every line is the same variable the axis can say so; a mixed panel
  // can only honestly say "value", plus whatever transform is in force.
  const variables = new Set(
    [...series.map((entry) => entry.key), ...bands.map((band) => band.base)].map((key) =>
      key.split(".").slice(1).join("."),
    ),
  );
  const axisLabel =
    transform === "raw"
      ? variables.size === 1
        ? [...variables][0]
        : "value"
      : TRANSFORM_AXIS[transform];

  // Three different nothings, which the panel used to report as one. No series picked is a
  // question for the reader; a request in flight is not their problem; a completed request with no
  // points means the range genuinely holds none.
  const nothingPicked = panel.seriesKeys.length === 0;
  const allHidden = !nothingPicked && raw.length === 0 && hidden.length > 0;
  const noPoints = presented.every((entry) => entry.points.length === 0);
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
            : `${series.length + bands.length} series · every sampled tick`
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
          hidden={hidden}
          colours={panel.colours ?? {}}
          objectTypes={objectTypes}
          groups={groups}
          onToggleHidden={(key) =>
            onChange({
              ...panel,
              hidden: hidden.includes(key) ? hidden.filter((item) => item !== key) : [...hidden, key],
            })
          }
          onSetColour={(key, colour) =>
            onChange({
              ...panel,
              colours: colour
                ? { ...(panel.colours ?? {}), [key]: colour }
                : // Removed rather than set to empty, so clearing an override returns the series to
                  // its palette slot instead of painting it with an invalid colour.
                  Object.fromEntries(
                    Object.entries(panel.colours ?? {}).filter(([existing]) => existing !== key),
                  ),
            })
          }
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
        {nothingPicked || allHidden ? (
          <div
            className="flex items-center justify-center text-xs text-[var(--text-muted)]"
            style={{ height: panelHeight }}
          >
            {allHidden
              ? `All ${hidden.length} series in this panel are hidden — show one from the series list.`
              : "Pick one or more series to chart."}
          </div>
        ) : (
        <PanelBody
          phase={phase}
          error={data.error}
          height={panelHeight}
          emptyTitle="No samples in this range"
          emptyDescription="The run has not reached these ticks, or the series was not sampled here."
        >
        <TimeSeriesChart
          series={series}
          bands={bands}
          axisLabel={axisLabel}
          events={events}
          colours={panel.colours ?? {}}
          format={format}
          height={panelHeight}
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
  hidden,
  colours,
  objectTypes,
  groups,
  onToggle,
  onSetMany,
  onToggleHidden,
  onSetColour,
}: {
  catalogue: Map<string, SeriesDefinition[]>;
  selected: string[];
  hidden: string[];
  colours: Record<string, string>;
  objectTypes: Map<string, string>;
  groups: Map<string, { label: string; count: number }>;
  onToggle: (key: string) => void;
  onSetMany: (keys: string[], selected: boolean) => void;
  onToggleHidden: (key: string) => void;
  onSetColour: (key: string, colour: string | null) => void;
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
              const isHidden = hidden.includes(entry.seriesKey);
              const swatch = colours[entry.seriesKey] ?? seriesColor(entry.seriesKey);
              return (
                <span
                  key={entry.seriesKey}
                  className={cn(
                    "inline-flex items-center rounded border text-xs transition-colors",
                    active
                      ? "border-[var(--accent)] bg-[var(--accent)]/10 text-[var(--text-primary)]"
                      : "border-[var(--border)] text-[var(--text-secondary)]",
                    isHidden && "opacity-60",
                  )}
                >
                  {/* The colour well is only offered for a series actually on the chart: choosing a
                      colour for something not drawn is a setting with nothing to show for it. */}
                  {active ? (
                    <label
                      className="cursor-pointer px-1.5 py-1"
                      title="Choose this series' colour. Double-click the swatch to go back to the default."
                    >
                      <span
                        aria-hidden
                        className="block size-2 rounded-sm"
                        style={{ background: swatch }}
                        onDoubleClick={(event) => {
                          event.preventDefault();
                          onSetColour(entry.seriesKey, null);
                        }}
                      />
                      <input
                        type="color"
                        className="sr-only"
                        value={colours[entry.seriesKey] ?? "#4f8cff"}
                        onChange={(event) => onSetColour(entry.seriesKey, event.target.value)}
                        aria-label={`Colour for ${entry.seriesKey}`}
                      />
                    </label>
                  ) : (
                    <span
                      aria-hidden
                      className="mx-1.5 size-2 rounded-sm"
                      style={{ background: "var(--border-strong)" }}
                    />
                  )}

                  <button
                    type="button"
                    onClick={() => onToggle(entry.seriesKey)}
                    className="py-1 pr-1 hover:underline"
                    title={active ? "Remove from this panel" : "Add to this panel"}
                  >
                    <span className={cn(isHidden && "line-through")} title={entry.seriesKey}>
                      {describeSeriesKey(entry.seriesKey, groups)}
                    </span>
                  </button>

                  {active ? (
                    <button
                      type="button"
                      onClick={() => onToggleHidden(entry.seriesKey)}
                      className="px-1.5 py-1 text-[10px] text-[var(--text-muted)] hover:text-[var(--text-primary)]"
                      title={
                        isHidden
                          ? "Show this series again"
                          : "Hide this series without losing its colour or its place"
                      }
                      aria-pressed={isHidden}
                    >
                      {isHidden ? "◌" : "●"}
                    </button>
                  ) : null}
                </span>
              );
            })}
          </div>
        </div>
      ))}
      {hidden.length > 0 ? (
        <p className="mb-1 text-[11px] text-[var(--text-muted)]">
          {hidden.length} hidden — still fetched, so showing one again is instant.
        </p>
      ) : null}
      {selected.length - hidden.length > 8 ? (
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
