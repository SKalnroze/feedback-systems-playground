import type { SeriesPoint, SeriesView } from "@/api/types";
import {
  extractBands,
  percentOfStartUndefined,
  presentSeries,
  smooth,
  transformPoints,
} from "@/features/runs/seriesTransforms";
import { describe, expect, it } from "vitest";

const points = (...values: number[]): SeriesPoint[] =>
  values.map((value, index) => [index, value] as SeriesPoint);

const view = (key: string, ...values: number[]): SeriesView => ({
  key,
  points: points(...values),
  bucketed: false,
});

describe("presentation transforms", () => {
  it("leaves raw values alone", () => {
    const input = points(1, 2, 3);
    expect(transformPoints(input, "raw")).toBe(input);
  });

  it("normalises a series against its own range", () => {
    expect(transformPoints(points(10, 20, 30), "normalised").map(([, v]) => v)).toEqual([0, 0.5, 1]);
  });

  it("leaves a flat series flat rather than inventing a position for it", () => {
    // Nothing moved, so there is no range to place it in. Mapping it to 0 would claim it sat at a
    // minimum; mapping it to 0.5 would claim a middle. Both are fabrications.
    const flat = points(0.4, 0.4, 0.4);
    expect(transformPoints(flat, "normalised").map(([, v]) => v)).toEqual([0.4, 0.4, 0.4]);
  });

  it("expresses a series as a percentage of where it started", () => {
    expect(transformPoints(points(50, 75, 100), "percentOfStart").map(([, v]) => v)).toEqual([
      100, 150, 200,
    ]);
  });

  it("falls back to raw when a series starts at zero", () => {
    // Resentment and workload commonly start at zero; dividing by it is undefined, so the series is
    // shown as recorded rather than as Infinity.
    const fromZero = points(0, 0.2, 0.4);
    expect(transformPoints(fromZero, "percentOfStart").map(([, v]) => v)).toEqual([0, 0.2, 0.4]);
    expect(percentOfStartUndefined(fromZero)).toBe(true);
    expect(percentOfStartUndefined(points(0.5, 0.6))).toBe(false);
  });

  it("starts every series at zero when showing change from start", () => {
    expect(transformPoints(points(5, 7, 4), "changeFromStart").map(([, v]) => v)).toEqual([0, 2, -1]);
  });

  it("handles an empty series without throwing", () => {
    for (const transform of ["raw", "normalised", "percentOfStart", "changeFromStart"] as const) {
      expect(transformPoints([], transform)).toEqual([]);
    }
  });
});

describe("smoothing", () => {
  it("returns the series unchanged for a window of one", () => {
    const input = points(1, 9, 1);
    expect(smooth(input, 1)).toBe(input);
  });

  it("averages over the window", () => {
    // Window 3 centred: [1,2,3] -> ends clipped, middle is the mean of all three.
    expect(smooth(points(1, 2, 3), 3).map(([, v]) => v)).toEqual([1.5, 2, 2.5]);
  });

  it("divides by the window actually available at the ends", () => {
    // The first point averages only itself and its right neighbour. Dividing by the full window
    // would drag the start of every smoothed line towards zero.
    const smoothed = smooth(points(10, 10, 10, 10), 3).map(([, v]) => v);
    expect(smoothed).toEqual([10, 10, 10, 10]);
  });

  it("spreads a spike symmetrically rather than shifting it", () => {
    // The real property of a centred window: the result is a mirror image around the spike. A
    // trailing average would push the whole feature to the right and misreport when it happened.
    // (Symmetry, not argmax — a symmetric window over one spike gives a plateau, not a single peak.)
    const smoothed = smooth(points(0, 0, 10, 0, 0), 3).map(([, v]) => v);
    expect(smoothed[1]).toBeCloseTo(smoothed[3] ?? 0, 10);
    expect(smoothed[0]).toBeCloseTo(smoothed[4] ?? 0, 10);
    expect(smoothed[2]).toBeGreaterThan(smoothed[0] ?? 0);
  });

  it("preserves tick positions", () => {
    expect(smooth(points(1, 2, 3), 3).map(([tick]) => tick)).toEqual([0, 1, 2]);
  });
});

describe("group bands", () => {
  it("pairs a mean with its min and max", () => {
    const { bands, rest } = extractBands([
      view("staff.morale.mean", 0.5, 0.6),
      view("staff.morale.min", 0.1, 0.2),
      view("staff.morale.max", 0.9, 0.95),
      view("supervisor.morale", 0.8, 0.8),
    ]);

    expect(bands).toHaveLength(1);
    expect(bands[0]?.base).toBe("staff.morale");
    expect(bands[0]?.mean.map(([, v]) => v)).toEqual([0.5, 0.6]);
    // The single object is not part of a band and stays an ordinary line.
    expect(rest.map((entry) => entry.key)).toEqual(["supervisor.morale"]);
  });

  it("leaves an incomplete triple as ordinary lines", () => {
    // Picking mean and max but not min is a legitimate selection, and inventing the missing edge
    // would draw a band the data does not support.
    const { bands, rest } = extractBands([
      view("staff.morale.mean", 0.5),
      view("staff.morale.max", 0.9),
    ]);

    expect(bands).toHaveLength(0);
    expect(rest).toHaveLength(2);
  });

  it("handles several groups at once", () => {
    const { bands } = extractBands([
      view("staff.morale.mean", 1),
      view("staff.morale.min", 0),
      view("staff.morale.max", 2),
      view("team.energy.mean", 1),
      view("team.energy.min", 0),
      view("team.energy.max", 2),
    ]);
    expect(bands.map((band) => band.base).sort()).toEqual(["staff.morale", "team.energy"]);
  });
});

describe("presenting a whole panel", () => {
  it("applies the transform and then the smoothing", () => {
    const result = presentSeries([view("a", 0, 10, 20)], "changeFromStart", 3);
    // change-from-start gives [0,10,20]; smoothing with window 3 gives [5,10,15].
    expect(result[0]?.points.map(([, v]) => v)).toEqual([5, 10, 15]);
  });
});
