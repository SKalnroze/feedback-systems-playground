import type { SeriesDefinition, SeriesView } from "@/api/types";
import { useSeriesCatalogue, useSeriesData } from "@/api/queries";
import { TimeSeriesChart } from "@/components/charts/TimeSeriesChart";
import { Badge, Button, Card, CardHeader, EmptyState, Select } from "@/components/ui/primitives";
import { cn, seriesColor, seriesLabel } from "@/lib/utils";
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
  onTickClick,
}: {
  runId: string;
  panels: ChartPanelConfig[];
  onPanelsChange: (panels: ChartPanelConfig[]) => void;
  theme: string;
  live: boolean;
  markers: { tick: number; label: string }[];
  onTickClick?: (tick: number) => void;
}) {
  const catalogue = useSeriesCatalogue(runId);
  const [resolution, setResolution] = useState(0);
  const [range, setRange] = useState<{ from: number; to: number } | null>(null);

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
          range={range}
          markers={markers}
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
  range,
  markers,
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
  range: { from: number; to: number } | null;
  markers: { tick: number; label: string }[];
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
          onToggle={(key) =>
            onChange({
              ...panel,
              seriesKeys: panel.seriesKeys.includes(key)
                ? panel.seriesKeys.filter((item) => item !== key)
                : [...panel.seriesKeys, key],
            })
          }
        />
      ) : null}

      <div className="px-2 pb-2">
        <TimeSeriesChart
          series={series}
          theme={theme}
          markers={markers}
          range={range}
          onRangeChange={onRangeChange}
          onTickClick={onTickClick}
        />
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
  onToggle,
}: {
  catalogue: Map<string, SeriesDefinition[]>;
  selected: string[];
  onToggle: (key: string) => void;
}) {
  if (catalogue.size === 0) {
    return (
      <p className="px-4 py-3 text-xs text-[var(--text-muted)]">
        No series recorded yet — step the run a few ticks.
      </p>
    );
  }

  return (
    <div className="max-h-56 overflow-auto border-b border-[var(--border)] px-4 py-3">
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
