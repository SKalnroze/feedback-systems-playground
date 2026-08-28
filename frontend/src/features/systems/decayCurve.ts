import type { DecayModel } from "@/api/types";

/**
 * The forgetting curves, mirrored on the client.
 *
 * Duplicating the maths is a deliberate trade: the editor needs the curve to redraw as a slider
 * moves, and a round trip per frame would make choosing a decay model feel like guesswork rather
 * than like drawing. Only the shape is duplicated - the authoritative evaluation, the one a run
 * actually uses, stays in the engine.
 *
 * Reactivation is not modelled here. A preview of an unrehearsed memory is the honest thing to
 * show: what reactivation does to the curve depends on triggers that fire at run time.
 */
export function decayStrength(model: DecayModel, elapsed: number, initial = 1): number {
  const raw = curve(model, elapsed, initial);
  const floor = model.common?.floor ?? 0;
  return Math.min(1, Math.max(raw, floor));
}

function curve(model: DecayModel, elapsed: number, initial: number): number {
  switch (model.kind) {
    case "none":
      return initial;
    case "exponential":
      return initial * Math.pow(2, -elapsed / Math.max(1e-9, model.halfLife));
    case "power-law":
      return initial * Math.pow(1 + elapsed, -model.exponent);
    case "ebbinghaus":
      return initial * Math.exp(-elapsed / Math.max(1e-9, model.baseStability));
    case "linear":
      return Math.max(0, initial - model.ratePerTick * elapsed);
    case "logistic": {
      const normaliser = 1 + Math.exp(-model.steepness * model.midpoint);
      return (initial * normaliser) / (1 + Math.exp(model.steepness * (elapsed - model.midpoint)));
    }
    case "step-threshold": {
      let retention = 1;
      for (const step of [...model.steps].sort((a, b) => a.afterTicks - b.afterTicks)) {
        if (elapsed >= step.afterTicks) retention = step.retention;
        else break;
      }
      return initial * retention;
    }
    case "act-r-base-level": {
      const lag = Math.max(1, elapsed);
      const activation = Math.log(Math.pow(lag, -model.decayExponent));
      const probability = 1 / (1 + Math.exp((model.threshold - activation) / model.noiseScale));
      return initial * probability;
    }
    default:
      return initial;
  }
}

/** Sample points for the preview, as `[elapsed, strength]`. */
export function decayCurvePoints(model: DecayModel, horizon: number, samples = 120): [number, number][] {
  const points: [number, number][] = [];
  for (let i = 0; i <= samples; i++) {
    const elapsed = (i / samples) * horizon;
    points.push([elapsed, decayStrength(model, elapsed)]);
  }
  return points;
}

/**
 * The first tick at which strength has reached a threshold, or null if it never does.
 *
 * Inclusive on purpose: a memory with a half-life of 25 is exactly half gone at tick 25, and a
 * label reading 26 would be quietly wrong.
 */
export function halfLifeOf(model: DecayModel, threshold = 0.5, horizon = 5000): number | null {
  for (let tick = 0; tick <= horizon; tick++) {
    if (decayStrength(model, tick) <= threshold) return tick;
  }
  return null;
}

/**
 * A sensible starting point for each model.
 *
 * Typed so that each key yields its own variant rather than the whole union: picking the
 * exponential preset and overriding its half-life should stay an exponential model, which a plain
 * `Record<kind, DecayModel>` would widen away.
 */
export const DECAY_PRESETS: { [K in DecayModel["kind"]]: Extract<DecayModel, { kind: K }> } = {
  none: { kind: "none", common: { floor: 0, interference: 0 } },
  exponential: { kind: "exponential", halfLife: 50, common: { floor: 0, interference: 0 } },
  "power-law": { kind: "power-law", exponent: 0.5, common: { floor: 0, interference: 0 } },
  ebbinghaus: {
    kind: "ebbinghaus",
    baseStability: 40,
    stabilityGrowth: 5,
    common: { floor: 0, interference: 0 },
  },
  linear: { kind: "linear", ratePerTick: 0.01, common: { floor: 0, interference: 0 } },
  logistic: { kind: "logistic", steepness: 0.3, midpoint: 40, common: { floor: 0, interference: 0 } },
  "step-threshold": {
    kind: "step-threshold",
    steps: [
      { afterTicks: 20, retention: 0.6 },
      { afterTicks: 100, retention: 0.2 },
    ],
    common: { floor: 0, interference: 0 },
  },
  "act-r-base-level": {
    kind: "act-r-base-level",
    decayExponent: 0.5,
    threshold: 0,
    noiseScale: 0.4,
    common: { floor: 0, interference: 0 },
  },
};

export const DECAY_DESCRIPTIONS: Record<DecayModel["kind"], string> = {
  none: "Never fades. Useful as a baseline, or for facts rather than experiences.",
  exponential: "A fixed proportion lost per tick. The default when nothing better is known.",
  "power-law": "Fast at first, then a very long tail. Fits human retention better than exponential.",
  ebbinghaus: "Exponential, but each rehearsal slows later forgetting - the spacing effect.",
  linear: "A constant amount lost per tick. Unrealistic for recall; useful for resource-like traces.",
  logistic: "Holds, then falls away quickly around a horizon. For memories with a shelf life.",
  "step-threshold": "Drops in stages: vivid, then the gist, then just a name.",
  "act-r-base-level": "Recency and frequency together. Spaced rehearsal wins without extra settings.",
};
