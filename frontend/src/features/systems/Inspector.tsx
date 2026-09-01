import type { Aggregate, CouplingMode, DecayModel, LinkSpec, SystemSpec, Transfer } from "@/api/types";
import { Badge, Button, Field, Input, Select } from "@/components/ui/primitives";
import {
  DECAY_DESCRIPTIONS,
  DECAY_PRESETS,
  decayCurvePoints,
  halfLifeOf,
} from "@/features/systems/decayCurve";
import {
  COUPLING_LABEL,
  COUPLING_MODES,
  couplingIsInvalid,
  explainCoupling,
} from "@/features/systems/couplingText";
import { cn, formatNumber } from "@/lib/utils";
import { useMemo } from "react";

/**
 * The properties panel.
 *
 * Every control edits the specification directly and immediately: there is no apply step, because
 * the draft is autosaved and nothing is published until asked for. The decay editor draws its
 * curve as the numbers change, since "half-life 40" means very little until you see what it does.
 */

export function LinkInspector({
  link,
  onChange,
  onDelete,
  memberCounts,
}: {
  link: LinkSpec;
  onChange: (link: LinkSpec) => void;
  onDelete: () => void;
  /** How many instances each authored object stands for, so coupling can be explained concretely. */
  memberCounts?: Map<string, number>;
}) {
  const sourceMembers = membersBehind(link.source, memberCounts);
  const targetMembers = membersBehind(link.target, memberCounts);
  const grouped = sourceMembers > 1 || targetMembers > 1;
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">Link</h3>
        <Button size="sm" variant="ghost" onClick={onDelete}>
          Delete
        </Button>
      </div>

      <p className="text-xs text-[var(--text-muted)]">
        {describeRef(link.source)} <span aria-hidden>→</span> {describeRef(link.target)}
      </p>

      {/* Only shown when it can matter. Between two single objects there is exactly one thing a
          link can mean, and offering six ways to say it would be noise. */}
      {grouped ? (
        <div className="space-y-2 rounded border border-[var(--border)] p-2">
          <Field label="How members connect">
            <Select
              className="w-full"
              value={link.coupling.mode}
              onChange={(event) =>
                onChange({
                  ...link,
                  coupling: { ...link.coupling, mode: event.target.value as CouplingMode },
                })
              }
            >
              {COUPLING_MODES.map((mode) => (
                <option key={mode} value={mode}>
                  {COUPLING_LABEL[mode]}
                </option>
              ))}
            </Select>
          </Field>

          {link.coupling.mode === "MANY_TO_ONE" ||
          link.coupling.mode === "MANY_TO_MANY_AGGREGATE" ||
          link.coupling.mode === "AUTO" ? (
            <Field label="Combined by">
              <Select
                className="w-full"
                value={link.coupling.aggregate}
                onChange={(event) =>
                  onChange({
                    ...link,
                    coupling: { ...link.coupling, aggregate: event.target.value as Aggregate },
                  })
                }
              >
                {(["MEAN", "SUM", "MIN", "MAX", "SPREAD", "VARIANCE"] as Aggregate[]).map((option) => (
                  <option key={option} value={option}>
                    {option.toLowerCase()}
                  </option>
                ))}
              </Select>
            </Field>
          ) : null}

          <p
            className={cn(
              "text-xs",
              couplingIsInvalid(link.coupling, sourceMembers, targetMembers)
                ? "text-[var(--status-critical)]"
                : "text-[var(--text-muted)]",
            )}
          >
            {explainCoupling(
              link.coupling,
              sourceMembers,
              targetMembers,
              describeRef(link.source),
              describeRef(link.target),
            )}
          </p>
        </div>
      ) : null}

      <Field
        label="Gain"
        hint="Negative gains oppose change; a loop with an odd number of them is balancing."
      >
        <Input
          className="tabular"
          type="number"
          step="0.05"
          value={link.gain}
          onChange={(event) => onChange({ ...link, gain: Number(event.target.value) })}
        />
      </Field>

      <Field label="Delay (ticks)" hint="Delayed consequences are what make systems overshoot.">
        <Input
          className="tabular"
          type="number"
          min={0}
          value={link.delayTicks}
          onChange={(event) =>
            onChange({ ...link, delayTicks: Math.max(0, Number(event.target.value)) })
          }
        />
      </Field>

      <Field label="Response shape">
        <Select
          className="w-full"
          value={link.transfer.kind}
          onChange={(event) => onChange({ ...link, transfer: defaultTransfer(event.target.value) })}
        >
          <option value="transfer-linear">Linear — proportional</option>
          <option value="sigmoid">Sigmoid — slow, then fast, then saturating</option>
          <option value="tanh">Tanh — symmetric saturation</option>
          <option value="threshold">Threshold — nothing until a point is crossed</option>
          <option value="saturating">Saturating — clipped to a band</option>
          <option value="logarithmic">Logarithmic — diminishing returns</option>
          <option value="power">Power — accelerating or damping</option>
        </Select>
      </Field>

      <TransferFields transfer={link.transfer} onChange={(transfer) => onChange({ ...link, transfer })} />

      <label className="flex items-center gap-2 text-xs text-[var(--text-secondary)]">
        <input
          type="checkbox"
          checked={link.usesRate}
          onChange={(event) => onChange({ ...link, usesRate: event.target.checked })}
        />
        Respond to change rather than level
      </label>
    </div>
  );
}

/** A transfer function of the chosen shape, with parameters that do something visible. */
function defaultTransfer(kind: string): Transfer {
  switch (kind) {
    case "sigmoid":
      return { kind: "sigmoid", steepness: 1, midpoint: 0, amplitude: 1 };
    case "tanh":
      return { kind: "tanh", scale: 1, amplitude: 1 };
    case "threshold":
      return { kind: "threshold", threshold: 0.5, below: 0, above: 1 };
    case "saturating":
      return { kind: "saturating", min: -1, max: 1 };
    case "logarithmic":
      return { kind: "logarithmic", scale: 1 };
    case "power":
      return { kind: "power", exponent: 2 };
    default:
      return { kind: "transfer-linear" };
  }
}

function TransferFields({
  transfer,
  onChange,
}: {
  transfer: Transfer;
  onChange: (transfer: Transfer) => void;
}) {
  switch (transfer.kind) {
    case "sigmoid":
      return (
        <div className="grid grid-cols-3 gap-2">
          <NumberField
            label="Steepness"
            value={transfer.steepness}
            onChange={(value) => onChange({ ...transfer, steepness: value })}
          />
          <NumberField
            label="Midpoint"
            value={transfer.midpoint}
            onChange={(value) => onChange({ ...transfer, midpoint: value })}
          />
          <NumberField
            label="Amplitude"
            value={transfer.amplitude}
            onChange={(value) => onChange({ ...transfer, amplitude: value })}
          />
        </div>
      );
    case "tanh":
      return (
        <div className="grid grid-cols-2 gap-2">
          <NumberField
            label="Scale"
            value={transfer.scale}
            onChange={(value) => onChange({ ...transfer, scale: value })}
          />
          <NumberField
            label="Amplitude"
            value={transfer.amplitude}
            onChange={(value) => onChange({ ...transfer, amplitude: value })}
          />
        </div>
      );
    case "threshold":
      return (
        <div className="grid grid-cols-3 gap-2">
          <NumberField
            label="At"
            value={transfer.threshold}
            onChange={(value) => onChange({ ...transfer, threshold: value })}
          />
          <NumberField
            label="Below"
            value={transfer.below}
            onChange={(value) => onChange({ ...transfer, below: value })}
          />
          <NumberField
            label="Above"
            value={transfer.above}
            onChange={(value) => onChange({ ...transfer, above: value })}
          />
        </div>
      );
    case "saturating":
      return (
        <div className="grid grid-cols-2 gap-2">
          <NumberField
            label="Min"
            value={transfer.min}
            onChange={(value) => onChange({ ...transfer, min: value })}
          />
          <NumberField
            label="Max"
            value={transfer.max}
            onChange={(value) => onChange({ ...transfer, max: value })}
          />
        </div>
      );
    case "logarithmic":
      return (
        <NumberField
          label="Scale"
          value={transfer.scale}
          onChange={(value) => onChange({ ...transfer, scale: value })}
        />
      );
    case "power":
      return (
        <NumberField
          label="Exponent"
          value={transfer.exponent}
          onChange={(value) => onChange({ ...transfer, exponent: value })}
        />
      );
    default:
      return null;
  }
}

function NumberField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: number;
  onChange: (value: number) => void;
}) {
  return (
    <Field label={label}>
      <Input
        className="tabular"
        type="number"
        step="0.1"
        value={value}
        onChange={(event) => onChange(Number(event.target.value))}
      />
    </Field>
  );
}

/** Decay model editor, with the curve it produces drawn beside it. */
export function DecayInspector({
  model,
  onChange,
}: {
  model: DecayModel;
  onChange: (model: DecayModel) => void;
}) {
  const horizon = useMemo(() => {
    const half = halfLifeOf(model, 0.5) ?? 100;
    // Show roughly four half-lives, which is where every curve here has clearly settled.
    return Math.max(20, Math.min(2000, half * 4));
  }, [model]);

  const points = useMemo(() => decayCurvePoints(model, horizon), [model, horizon]);
  const path = useMemo(() => {
    return points
      .map(([elapsed, strength], index) => {
        const x = (elapsed / horizon) * 100;
        const y = 100 - strength * 100;
        return `${index === 0 ? "M" : "L"}${x.toFixed(2)},${y.toFixed(2)}`;
      })
      .join(" ");
  }, [points, horizon]);

  const half = halfLifeOf(model, 0.5);

  return (
    <div className="space-y-3">
      <Field label="Forgetting curve" hint={DECAY_DESCRIPTIONS[model.kind]}>
        <Select
          className="w-full"
          value={model.kind}
          onChange={(event) => onChange(DECAY_PRESETS[event.target.value as DecayModel["kind"]])}
        >
          {Object.keys(DECAY_PRESETS).map((kind) => (
            <option key={kind} value={kind}>
              {kind}
            </option>
          ))}
        </Select>
      </Field>

      <div className="rounded-md border border-[var(--border)] bg-[var(--surface-0)] p-2">
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="h-24 w-full" role="img"
             aria-label="Preview of the forgetting curve">
          <line x1="0" y1="50" x2="100" y2="50" stroke="var(--grid)" strokeWidth="0.5" vectorEffect="non-scaling-stroke" />
          <line x1="0" y1="100" x2="100" y2="100" stroke="var(--axis)" strokeWidth="1" vectorEffect="non-scaling-stroke" />
          <path
            d={path}
            fill="none"
            stroke="var(--accent)"
            strokeWidth="2"
            vectorEffect="non-scaling-stroke"
          />
        </svg>
        <div className="mt-1 flex items-center justify-between text-[10px] text-[var(--text-muted)]">
          <span>0</span>
          <span>
            {half === null ? "never halves" : `half gone at ${half} ticks`}
          </span>
          <span>{Math.round(horizon)} ticks</span>
        </div>
      </div>

      <DecayFields model={model} onChange={onChange} />

      <div className="grid grid-cols-2 gap-2">
        <NumberField
          label="Floor"
          value={model.common.floor}
          onChange={(value) =>
            onChange({ ...model, common: { ...model.common, floor: clamp01(value) } } as DecayModel)
          }
        />
        <NumberField
          label="Interference"
          value={model.common.interference}
          onChange={(value) =>
            onChange({
              ...model,
              common: { ...model.common, interference: Math.max(0, value) },
            } as DecayModel)
          }
        />
      </div>
      <p className="text-[11px] text-[var(--text-muted)]">
        A floor keeps part of every memory permanently. Interference lets similar memories crowd
        each other out — it costs more to compute, so leave it at zero unless the model needs it.
      </p>
    </div>
  );
}

function DecayFields({
  model,
  onChange,
}: {
  model: DecayModel;
  onChange: (model: DecayModel) => void;
}) {
  switch (model.kind) {
    case "exponential":
      return (
        <NumberField
          label="Half-life (ticks)"
          value={model.halfLife}
          onChange={(value) => onChange({ ...model, halfLife: Math.max(0.1, value) })}
        />
      );
    case "power-law":
      return (
        <NumberField
          label="Exponent"
          value={model.exponent}
          onChange={(value) => onChange({ ...model, exponent: Math.max(0.01, value) })}
        />
      );
    case "ebbinghaus":
      return (
        <div className="grid grid-cols-2 gap-2">
          <NumberField
            label="Base stability"
            value={model.baseStability}
            onChange={(value) => onChange({ ...model, baseStability: Math.max(0.1, value) })}
          />
          <NumberField
            label="Growth per rehearsal"
            value={model.stabilityGrowth}
            onChange={(value) => onChange({ ...model, stabilityGrowth: Math.max(0, value) })}
          />
        </div>
      );
    case "linear":
      return (
        <NumberField
          label="Lost per tick"
          value={model.ratePerTick}
          onChange={(value) => onChange({ ...model, ratePerTick: Math.max(0, value) })}
        />
      );
    case "logistic":
      return (
        <div className="grid grid-cols-2 gap-2">
          <NumberField
            label="Steepness"
            value={model.steepness}
            onChange={(value) => onChange({ ...model, steepness: Math.max(0.01, value) })}
          />
          <NumberField
            label="Midpoint"
            value={model.midpoint}
            onChange={(value) => onChange({ ...model, midpoint: Math.max(1, value) })}
          />
        </div>
      );
    case "act-r-base-level":
      return (
        <div className="grid grid-cols-3 gap-2">
          <NumberField
            label="Decay"
            value={model.decayExponent}
            onChange={(value) => onChange({ ...model, decayExponent: Math.max(0.01, value) })}
          />
          <NumberField
            label="Threshold"
            value={model.threshold}
            onChange={(value) => onChange({ ...model, threshold: value })}
          />
          <NumberField
            label="Noise"
            value={model.noiseScale}
            onChange={(value) => onChange({ ...model, noiseScale: Math.max(0.01, value) })}
          />
        </div>
      );
    case "step-threshold":
      return (
        <div className="space-y-1">
          {model.steps.map((step, index) => (
            <div key={index} className="grid grid-cols-2 gap-2">
              <NumberField
                label={`After (ticks)`}
                value={step.afterTicks}
                onChange={(value) =>
                  onChange({
                    ...model,
                    steps: model.steps.map((item, position) =>
                      position === index ? { ...item, afterTicks: Math.max(0, value) } : item,
                    ),
                  })
                }
              />
              <NumberField
                label="Retention"
                value={step.retention}
                onChange={(value) =>
                  onChange({
                    ...model,
                    steps: model.steps.map((item, position) =>
                      position === index ? { ...item, retention: clamp01(value) } : item,
                    ),
                  })
                }
              />
            </div>
          ))}
          <Button
            size="sm"
            onClick={() =>
              onChange({
                ...model,
                steps: [
                  ...model.steps,
                  {
                    afterTicks: (model.steps.at(-1)?.afterTicks ?? 0) + 100,
                    retention: Math.max(0, (model.steps.at(-1)?.retention ?? 0.5) / 2),
                  },
                ],
              })
            }
          >
            + Step
          </Button>
        </div>
      );
    default:
      return null;
  }
}

/** Summary of the loops validation found, which is the quickest read on what a system will do. */
export function LoopSummary({ report }: { report: { polarity: string; totalDelay: number }[] }) {
  if (!report.length) {
    return (
      <p className="text-xs text-[var(--text-muted)]">
        No feedback loops yet — every link runs one way, so nothing can compound.
      </p>
    );
  }
  const reinforcing = report.filter((loop) => loop.polarity === "REINFORCING").length;
  const balancing = report.length - reinforcing;
  const slow = report.filter((loop) => loop.polarity === "BALANCING" && loop.totalDelay >= 2).length;

  return (
    <div className="space-y-1.5">
      <div className="flex flex-wrap gap-1.5">
        {reinforcing > 0 ? <Badge tone="critical">{reinforcing} reinforcing</Badge> : null}
        {balancing > 0 ? <Badge tone="accent">{balancing} balancing</Badge> : null}
        {slow > 0 ? <Badge tone="warning">{slow} delayed balancing</Badge> : null}
      </div>
      <p className="text-[11px] text-[var(--text-muted)]">
        Reinforcing loops make deviations grow; balancing loops pull back towards equilibrium. A
        balancing loop with a delay is the classic recipe for oscillation.
      </p>
    </div>
  );
}

/** Members behind an endpoint: a group's count, or one for anything that is not a group. */
function membersBehind(
  ref: SystemSpec["links"][number]["source"],
  counts: Map<string, number> | undefined,
): number {
  return ref.kind === "object" ? (counts?.get(ref.objectId) ?? 1) : 1;
}

function describeRef(ref: SystemSpec["links"][number]["source"]): string {
  switch (ref.kind) {
    case "global":
      return `global ${ref.name}`;
    case "object":
      return `${ref.objectId}.${ref.name}`;
    case "type-aggregate":
      return `${ref.aggregate.toLowerCase()} ${ref.typeId}.${ref.name}`;
  }
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value));
}

export { formatNumber };
