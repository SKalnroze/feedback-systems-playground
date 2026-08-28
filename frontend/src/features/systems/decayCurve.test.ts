import { DECAY_PRESETS, decayCurvePoints, decayStrength, halfLifeOf } from "@/features/systems/decayCurve";
import { describe, expect, it } from "vitest";

/**
 * The preview curves have to agree with the engine, or the editor teaches the wrong thing: an
 * author would pick a half-life from a picture and get different behaviour when they ran it.
 * These are the same known values the engine's own decay tests assert.
 */
describe("decay curves", () => {
  it("halves an exponential memory every half-life", () => {
    const model = { ...DECAY_PRESETS.exponential, halfLife: 10 } as const;

    expect(decayStrength(model, 0)).toBeCloseTo(1, 6);
    expect(decayStrength(model, 10)).toBeCloseTo(0.5, 6);
    expect(decayStrength(model, 20)).toBeCloseTo(0.25, 6);
  });

  it("follows the power law", () => {
    const model = { ...DECAY_PRESETS["power-law"], exponent: 0.5 } as const;

    expect(decayStrength(model, 3)).toBeCloseTo(0.5, 6);
    expect(decayStrength(model, 99)).toBeCloseTo(0.1, 6);
  });

  it("leaves a longer tail than the exponential", () => {
    const power = { ...DECAY_PRESETS["power-law"], exponent: 0.5 } as const;
    const exponential = { ...DECAY_PRESETS.exponential, halfLife: 3 } as const;

    expect(decayStrength(power, 200)).toBeGreaterThan(decayStrength(exponential, 200));
  });

  it("never falls below the floor", () => {
    const model = {
      ...DECAY_PRESETS.exponential,
      halfLife: 1,
      common: { floor: 0.2, interference: 0 },
    } as const;

    expect(decayStrength(model, 10_000)).toBeCloseTo(0.2, 6);
  });

  it("holds a logistic curve flat before its midpoint", () => {
    const model = { ...DECAY_PRESETS.logistic, steepness: 0.8, midpoint: 30 } as const;

    expect(decayStrength(model, 10)).toBeGreaterThan(0.99);
    expect(decayStrength(model, 45)).toBeLessThan(0.01);
  });

  it("steps down at each threshold", () => {
    const model = DECAY_PRESETS["step-threshold"];

    expect(decayStrength(model, 19)).toBeCloseTo(1, 6);
    expect(decayStrength(model, 20)).toBeCloseTo(0.6, 6);
    expect(decayStrength(model, 100)).toBeCloseTo(0.2, 6);
  });

  it("never leaves the unit interval, for any model", () => {
    for (const model of Object.values(DECAY_PRESETS)) {
      for (const elapsed of [0, 1, 17, 500, 10_000]) {
        const strength = decayStrength(model, elapsed);
        expect(strength).toBeGreaterThanOrEqual(0);
        expect(strength).toBeLessThanOrEqual(1);
      }
    }
  });

  it("never grows over time without rehearsal", () => {
    for (const model of Object.values(DECAY_PRESETS)) {
      let previous = decayStrength(model, 0);
      for (let elapsed = 1; elapsed <= 500; elapsed += 7) {
        const current = decayStrength(model, elapsed);
        expect(current).toBeLessThanOrEqual(previous + 1e-9);
        previous = current;
      }
    }
  });

  it("reports where a curve loses half its strength", () => {
    expect(halfLifeOf({ ...DECAY_PRESETS.exponential, halfLife: 25 })).toBeCloseTo(25, 0);
    expect(halfLifeOf(DECAY_PRESETS.none)).toBeNull();
  });

  it("samples a preview that starts full and spans the horizon", () => {
    const points = decayCurvePoints({ ...DECAY_PRESETS.exponential, halfLife: 10 }, 100, 20);

    expect(points).toHaveLength(21);
    expect(points[0]).toEqual([0, 1]);
    expect(points.at(-1)?.[0]).toBe(100);
  });
});
