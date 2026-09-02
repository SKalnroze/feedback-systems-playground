import type { SeriesPoint, SeriesView } from "@/api/types";

/**
 * How values are presented, as distinct from what they are.
 *
 * The chart deliberately refuses a second y-axis: two scales on one plot let any pair of lines be
 * made to look as though they move together, which is the most misleading thing a chart of this kind
 * can do. But the underlying need is real — trust runs 0 to 1 and headcount runs 0 to 500, and a
 * reader still wants to know whether they turn at the same moment.
 *
 * These transforms answer that honestly. Every series stays on one axis; what changes is what the
 * axis measures, stated in its label so a normalised chart can never be misread as a raw one.
 */
export type SeriesTransform = "raw" | "normalised" | "percentOfStart" | "changeFromStart";

export const TRANSFORM_LABEL: Record<SeriesTransform, string> = {
  raw: "raw values",
  normalised: "normalised 0–1",
  percentOfStart: "% of starting value",
  changeFromStart: "change from start",
};

/** What the y-axis is measuring under each transform, for the axis title. */
export const TRANSFORM_AXIS: Record<SeriesTransform, string> = {
  raw: "value",
  normalised: "normalised (0–1)",
  percentOfStart: "% of start",
  changeFromStart: "Δ from start",
};

export const TRANSFORM_HINT: Record<SeriesTransform, string> = {
  raw: "Values exactly as recorded.",
  normalised: "Each series scaled to its own range, so shapes can be compared across scales.",
  percentOfStart: "Each series relative to its first value. Series starting at zero stay raw.",
  changeFromStart: "Each series minus its first value, so lines start together at zero.",
};

/**
 * Applies a transform to one series' points.
 *
 * A series is rescaled against its own range rather than the panel's: the point is to compare
 * shapes, and using a shared range would flatten every series but the largest back into a line at
 * the bottom of the chart, which is the problem being solved.
 */
export function transformPoints(points: SeriesPoint[], transform: SeriesTransform): SeriesPoint[] {
  if (transform === "raw" || points.length === 0) {
    return points;
  }

  if (transform === "normalised") {
    let min = Infinity;
    let max = -Infinity;
    for (const [, value] of points) {
      if (value < min) min = value;
      if (value > max) max = value;
    }
    const span = max - min;
    // A series that never moves has no range to normalise into. Mapping it to zero would suggest it
    // sat at its own minimum all run, and to 0.5 that it sat in the middle of something; both are
    // inventions. Left alone, a flat line stays a flat line.
    if (span === 0) {
      return points;
    }
    return points.map(([tick, value]) => [tick, (value - min) / span] as SeriesPoint);
  }

  const first = points[0]?.[1] ?? 0;

  if (transform === "changeFromStart") {
    return points.map(([tick, value]) => [tick, value - first] as SeriesPoint);
  }

  // percentOfStart: dividing by a starting value of zero is undefined, and a variable that begins at
  // zero is common (resentment, workload). Returning the raw series is the honest fallback, and the
  // legend says which series were left alone.
  if (first === 0) {
    return points;
  }
  return points.map(([tick, value]) => [tick, (value / first) * 100] as SeriesPoint);
}

/** True when a percent-of-start transform could not be applied to this series. */
export function percentOfStartUndefined(points: SeriesPoint[]): boolean {
  return points.length > 0 && points[0]?.[1] === 0;
}

/**
 * Moving average over a window of points.
 *
 * Centred rather than trailing, so a smoothed peak sits where the peak actually was; a trailing
 * average would shift every feature to the right by half the window and quietly mislead about when
 * something happened.
 *
 * @param window number of points to average over; 1 or less returns the series unchanged
 */
export function smooth(points: SeriesPoint[], window: number): SeriesPoint[] {
  if (window <= 1 || points.length === 0) {
    return points;
  }
  const half = Math.floor(window / 2);
  const smoothed: SeriesPoint[] = new Array(points.length);

  for (let index = 0; index < points.length; index++) {
    const from = Math.max(0, index - half);
    const to = Math.min(points.length - 1, index + half);
    let total = 0;
    for (let at = from; at <= to; at++) {
      total += points[at]?.[1] ?? 0;
    }
    // Divided by the window actually available, not the requested one: near the ends the window is
    // clipped, and dividing by the full width would drag the first and last points towards zero.
    smoothed[index] = [points[index]?.[0] ?? 0, total / (to - from + 1)];
  }
  return smoothed;
}

/** Applies transform then smoothing to every series in a panel. */
export function presentSeries(
  series: SeriesView[],
  transform: SeriesTransform,
  smoothingWindow: number,
): SeriesView[] {
  return series.map((entry) => ({
    ...entry,
    points: smooth(transformPoints(entry.points, transform), smoothingWindow),
  }));
}

// --- group bands ----------------------------------------------------------------------------------

/** A group's mean with the range its members actually occupied. */
export type SeriesBand = {
  key: string;
  /** The group and variable, e.g. "staff.morale". */
  base: string;
  mean: SeriesPoint[];
  min: SeriesPoint[];
  max: SeriesPoint[];
};

/**
 * Finds the mean/min/max triples a group produces and pairs them up.
 *
 * `SampleCollector` writes a group as three series. Drawn as three lines they compete for attention
 * and read as three unrelated variables; drawn as a band they read as what they are, which is one
 * variable and the spread around it. Anything without all three stays an ordinary line.
 */
export function extractBands(series: SeriesView[]): { bands: SeriesBand[]; rest: SeriesView[] } {
  const byBase = new Map<string, Map<string, SeriesView>>();
  for (const entry of series) {
    const match = /^(.*)\.(mean|min|max)$/.exec(entry.key);
    if (!match) continue;
    const [, base, statistic] = match;
    if (!base || !statistic) continue;
    const group = byBase.get(base) ?? new Map<string, SeriesView>();
    group.set(statistic, entry);
    byBase.set(base, group);
  }

  const bands: SeriesBand[] = [];
  const banded = new Set<string>();
  for (const [base, parts] of byBase) {
    const mean = parts.get("mean");
    const min = parts.get("min");
    const max = parts.get("max");
    if (!mean || !min || !max) continue;
    bands.push({ key: `${base}.band`, base, mean: mean.points, min: min.points, max: max.points });
    banded.add(mean.key);
    banded.add(min.key);
    banded.add(max.key);
  }

  return { bands, rest: series.filter((entry) => !banded.has(entry.key)) };
}
