import type { RunSummary } from "@/api/types";
import { api } from "@/api/client";
import { useRuns, useSeriesCatalogue } from "@/api/queries";
import { TimeSeriesChart } from "@/components/charts/TimeSeriesChart";
import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  ErrorNote,
  Field,
  Select,
  Spinner,
} from "@/components/ui/primitives";
import { useTheme } from "@/lib/theme";
import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";

/**
 * Putting runs side by side.
 *
 * Forking exists so that two histories can be compared, and until now the tool could produce the
 * fork and then show them only one at a time. A difference you have to hold in your head between
 * two page loads is not a comparison.
 *
 * Two modes, because there are two different questions. Overlay draws each run as its own line and
 * answers "how did these particular runs differ". Band aggregates them into mean and range and
 * answers "what does this configuration usually do" - the question a single run cannot answer at
 * all, however carefully you look at it.
 */
export function ComparePage() {
  const runs = useRuns();
  const { theme } = useTheme();
  const [selected, setSelected] = useState<string[]>([]);
  const [seriesKey, setSeriesKey] = useState("");
  const [mode, setMode] = useState<"overlay" | "band">("overlay");

  // The catalogue of the first selection: runs of the same system share series keys, and offering
  // a key that only one run has would produce a chart with one line and no explanation.
  const catalogue = useSeriesCatalogue(selected[0] ?? "");
  const available = catalogue.data ?? [];

  // Keyed by id, not filtered into a list: selection order and list order are different, and
  // pairing them by position labels each line with another run's name.
  const runsById = useMemo(
    () => new Map((runs.data ?? []).map((run) => [run.id, run])),
    [runs.data],
  );

  const overlay = useQuery({
    queryKey: ["compare", "overlay", selected, seriesKey],
    enabled: mode === "overlay" && selected.length > 0 && seriesKey !== "",
    queryFn: async () => {
      const responses = await Promise.all(
        selected.map((runId) => api.seriesData(runId, [seriesKey], 0, -1, 0)),
      );
      return responses.map((response, index) => ({
        name: runsById.get(selected[index] ?? "")?.name ?? selected[index] ?? "run",
        points: response.series[0]?.points ?? [],
      }));
    },
  });

  const band = useQuery({
    queryKey: ["compare", "band", selected, seriesKey],
    enabled: mode === "band" && selected.length > 1 && seriesKey !== "",
    queryFn: () => api.compareRuns(selected, seriesKey),
  });

  const chartSeries = useMemo(() => {
    if (mode === "overlay") {
      return (overlay.data ?? []).map((entry) => ({ key: entry.name, points: entry.points, bucketed: false }));
    }
    const bands = band.data?.bands ?? [];
    if (bands.length === 0) return [];
    // Min and max are drawn as their own lines rather than as a shaded ribbon: a band drawn as an
    // area needs a stacked series pair, and the extra machinery buys nothing a reader cannot get
    // from three labelled lines.
    return [
      { key: "mean", points: bands.map((entry) => [entry.tick, entry.mean] as [number, number]), bucketed: true },
      { key: "min", points: bands.map((entry) => [entry.tick, entry.min] as [number, number]), bucketed: true },
      { key: "max", points: bands.map((entry) => [entry.tick, entry.max] as [number, number]), bucketed: true },
    ];
  }, [mode, overlay.data, band.data]);

  function toggle(run: RunSummary) {
    setSelected((current) =>
      current.includes(run.id) ? current.filter((id) => id !== run.id) : [...current, run.id],
    );
  }

  const loading = overlay.isFetching || band.isFetching;
  const failure = overlay.error ?? band.error;

  return (
    <div className="mx-auto grid max-w-6xl gap-4 p-4 lg:grid-cols-[300px_1fr]">
      <Card className="h-fit">
        <CardHeader title="Runs" subtitle="Pick two or more to compare" />
        <div className="max-h-[420px] overflow-auto">
          {runs.data && runs.data.length > 0 ? (
            <ul className="divide-y divide-[var(--border)]">
              {runs.data.map((run) => (
                <li key={run.id}>
                  <label className="flex cursor-pointer items-center gap-2 px-3 py-2 hover:bg-[var(--surface-2)]">
                    <input
                      type="checkbox"
                      checked={selected.includes(run.id)}
                      onChange={() => toggle(run)}
                    />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm">{run.name}</span>
                      <span className="block truncate text-xs text-[var(--text-muted)]">
                        {run.systemName} · seed {run.seed}
                      </span>
                    </span>
                    {run.parentRunId ? <Badge tone="neutral">fork</Badge> : null}
                  </label>
                </li>
              ))}
            </ul>
          ) : (
            <EmptyState title="No runs yet" description="Start a run, then come back to compare it." />
          )}
        </div>
      </Card>

      <Card className="min-w-0">
        <CardHeader
          title="Comparison"
          subtitle={
            mode === "overlay"
              ? "One line per run, on a shared axis"
              : "Mean and range across the selected runs"
          }
          actions={loading ? <Spinner /> : null}
        />
        <div className="space-y-3 p-4">
          <div className="flex flex-wrap items-end gap-3">
            <Field label="Series">
              <Select
                className="w-56"
                value={seriesKey}
                onChange={(event) => setSeriesKey(event.target.value)}
                disabled={available.length === 0}
              >
                <option value="">Choose a series…</option>
                {available.map((definition) => (
                  <option key={definition.seriesKey} value={definition.seriesKey}>
                    {definition.seriesKey}
                  </option>
                ))}
              </Select>
            </Field>

            <div className="flex gap-1">
              <Button
                size="sm"
                variant={mode === "overlay" ? "primary" : "ghost"}
                onClick={() => setMode("overlay")}
              >
                Overlay
              </Button>
              <Button size="sm" variant={mode === "band" ? "primary" : "ghost"} onClick={() => setMode("band")}>
                Band
              </Button>
            </div>

            <span className="text-xs text-[var(--text-muted)]">
              {selected.length} run{selected.length === 1 ? "" : "s"} selected
            </span>
          </div>

          {failure ? (
            <ErrorNote message={failure instanceof Error ? failure.message : "Could not load the comparison."} />
          ) : null}

          {mode === "band" && selected.length < 2 ? (
            <EmptyState
              title="Pick at least two runs"
              description="A band is a spread across replicates; one run has no spread to show."
            />
          ) : chartSeries.length > 0 ? (
            <TimeSeriesChart series={chartSeries} theme={theme} height={360} />
          ) : (
            <EmptyState
              title="Nothing charted yet"
              description="Select some runs and a series to see them on one axis."
            />
          )}

          {mode === "band" && band.data && band.data.bands.length > 0 ? (
            <p className="text-xs text-[var(--text-muted)]">
              Widest spread{" "}
              {(() => {
                const widest = band.data.bands.reduce((worst, entry) =>
                  entry.max - entry.min > worst.max - worst.min ? entry : worst,
                );
                return `${(widest.max - widest.min).toFixed(2)} at tick ${widest.tick}`;
              })()}
              . A wide band means the configuration does not reliably produce the average.
            </p>
          ) : null}
        </div>
      </Card>
    </div>
  );
}
