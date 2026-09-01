import type { EventSpec, MemoryTrigger, ObjectSpec, SystemSpec } from "@/api/types";
import { Button, Field, Input, Select } from "@/components/ui/primitives";
import type { ReactNode } from "react";

/**
 * Editors for the parts of a system that used to be read-only.
 *
 * Objects, events and triggers all appeared on the canvas but could only be looked at, which made
 * the presets a dead end: the tool offered a working model to take apart and then refused to open
 * it.
 *
 * Generators and selectors are edited by kind, showing only the fields that kind actually has. A
 * generic editor over the underlying JSON would cover every case in a tenth of the code and teach
 * the author nothing about which knobs exist.
 */

function Row({ children }: { children: ReactNode }) {
  return <div className="grid grid-cols-2 gap-2">{children}</div>;
}

function NumberField({
  label,
  value,
  step = 0.05,
  onChange,
}: {
  label: string;
  value: number;
  step?: number;
  onChange: (value: number) => void;
}) {
  return (
    <Field label={label}>
      <Input
        className="tabular"
        type="number"
        step={step}
        value={Number.isFinite(value) ? value : 0}
        onChange={(event) => onChange(Number(event.target.value))}
      />
    </Field>
  );
}

function Header({ title, onDelete }: { title: string; onDelete: () => void }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <h3 className="truncate text-sm font-semibold">{title}</h3>
      <Button variant="ghost" size="sm" onClick={onDelete} title="Remove from the system">
        Delete
      </Button>
    </div>
  );
}

function commaList(value: string): string[] {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

/** An object instance: its starting values, and the tags rules match on. */
export function ObjectInspector({
  object,
  type,
  onChange,
  onDelete,
}: {
  object: ObjectSpec;
  type: SystemSpec["objectTypes"][number] | undefined;
  onChange: (object: ObjectSpec) => void;
  onDelete: () => void;
}) {
  // Variables come from the type rather than from whatever the object happens to override, so an
  // author can set a value that is currently defaulted without first knowing it was missing.
  const variables = type?.variables ?? [];
  return (
    <div className="space-y-3">
      <Header title={object.label || object.id} onDelete={onDelete} />
      <Row>
        <Field label="Label">
          <Input
            value={object.label}
            onChange={(event) => onChange({ ...object, label: event.target.value })}
          />
        </Field>
        <Field
          label="How many"
          hint={
            object.count > 1
              ? `${object.count.toLocaleString()} separate instances, each with its own memories`
              : "One object. Raise this to model a population."
          }
        >
          <Input
            className="tabular"
            type="number"
            min={1}
            step={1}
            value={object.count}
            onChange={(event) =>
              onChange({ ...object, count: Math.max(1, Math.round(Number(event.target.value) || 1)) })
            }
          />
        </Field>
      </Row>
      {object.count > 2000 ? (
        <p className="text-xs text-[var(--status-warning)]">
          Above about two thousand members every tick gets noticeably slower and checkpoints get large,
          because each member is a real object that remembers things.
        </p>
      ) : null}

      {variables.length > 0 ? (
        <div className="space-y-2">
          <div className="text-xs font-medium text-[var(--text-muted)]">Starting values</div>
          <Row>
            {variables.map((variable) => (
              <NumberField
                key={variable.name}
                label={variable.label || variable.name}
                value={object.variables[variable.name] ?? variable.initial}
                onChange={(value) =>
                  onChange({ ...object, variables: { ...object.variables, [variable.name]: value } })
                }
              />
            ))}
          </Row>
        </div>
      ) : null}

      <Field label="Tags" hint="Comma separated; selectors and rules can match on these.">
        <Input
          value={object.tags.join(", ")}
          onChange={(event) => onChange({ ...object, tags: commaList(event.target.value) })}
        />
      </Field>
    </div>
  );
}

const GENERATOR_KINDS = ["never", "fixed-schedule", "bernoulli", "poisson", "periodic", "burst"] as const;

function defaultGenerator(kind: string): EventSpec["generator"] {
  switch (kind) {
    case "fixed-schedule":
      return { kind: "fixed-schedule", ticks: [100] };
    case "bernoulli":
      return { kind: "bernoulli", probability: 0.01 };
    case "poisson":
      return { kind: "poisson", ratePerTick: 0.05 };
    case "periodic":
      return { kind: "periodic", interval: 50, jitter: 0, offset: 0 };
    case "burst":
      return { kind: "burst", baseRate: 0.01, excitation: 0.3, decay: 0.9 };
    default:
      return { kind: "never" };
  }
}

function GeneratorFields({
  generator,
  onChange,
}: {
  generator: EventSpec["generator"];
  onChange: (generator: EventSpec["generator"]) => void;
}) {
  switch (generator.kind) {
    case "fixed-schedule":
      return (
        <Field label="Ticks" hint="Comma separated tick numbers.">
          <Input
            className="tabular"
            value={generator.ticks.join(", ")}
            onChange={(event) =>
              onChange({
                ...generator,
                ticks: commaList(event.target.value)
                  .map((tick) => Number(tick))
                  .filter((tick) => Number.isFinite(tick)),
              })
            }
          />
        </Field>
      );
    case "bernoulli":
      return (
        <NumberField
          label="Probability per tick"
          value={generator.probability}
          onChange={(probability) => onChange({ ...generator, probability })}
        />
      );
    case "poisson":
      return (
        <NumberField
          label="Rate per tick"
          value={generator.ratePerTick}
          onChange={(ratePerTick) => onChange({ ...generator, ratePerTick })}
        />
      );
    case "periodic":
      return (
        <Row>
          <NumberField
            label="Interval"
            step={1}
            value={generator.interval}
            onChange={(interval) => onChange({ ...generator, interval })}
          />
          <NumberField
            label="Jitter"
            step={1}
            value={generator.jitter}
            onChange={(jitter) => onChange({ ...generator, jitter })}
          />
          <NumberField
            label="Offset"
            step={1}
            value={generator.offset}
            onChange={(offset) => onChange({ ...generator, offset })}
          />
        </Row>
      );
    case "burst":
      return (
        <Row>
          <NumberField
            label="Base rate"
            value={generator.baseRate}
            onChange={(baseRate) => onChange({ ...generator, baseRate })}
          />
          <NumberField
            label="Excitation"
            value={generator.excitation}
            onChange={(excitation) => onChange({ ...generator, excitation })}
          />
          <NumberField
            label="Decay"
            value={generator.decay}
            onChange={(decay) => onChange({ ...generator, decay })}
          />
        </Row>
      );
    default:
      return <p className="text-xs text-[var(--text-muted)]">This event never fires on its own.</p>;
  }
}

/** An external event: when it fires, who it lands on, and how often it may. */
export function EventInspector({
  event,
  objectTypes,
  onChange,
  onDelete,
}: {
  event: EventSpec;
  objectTypes: SystemSpec["objectTypes"];
  onChange: (event: EventSpec) => void;
  onDelete: () => void;
}) {
  const selector = event.selector;
  return (
    <div className="space-y-3">
      <Header title={event.label || event.id} onDelete={onDelete} />

      <Field label="Label">
        <Input
          value={event.label}
          onChange={(change) => onChange({ ...event, label: change.target.value })}
        />
      </Field>

      <Field label="When it fires">
        <Select
          className="w-full"
          value={event.generator.kind}
          onChange={(change) => onChange({ ...event, generator: defaultGenerator(change.target.value) })}
        >
          {GENERATOR_KINDS.map((kind) => (
            <option key={kind} value={kind}>
              {kind}
            </option>
          ))}
        </Select>
      </Field>
      <GeneratorFields generator={event.generator} onChange={(generator) => onChange({ ...event, generator })} />

      <Field label="Who it hits">
        <Select
          className="w-full"
          value={selector.kind}
          onChange={(change) => {
            const kind = change.target.value;
            const typeId = objectTypes[0]?.id ?? null;
            const next =
              kind === "everyone"
                ? ({ kind: "everyone", typeId } as const)
                : kind === "random-k"
                  ? ({ kind: "random-k", typeId, count: 1 } as const)
                  : kind === "random-pair"
                    ? ({ kind: "random-pair", typeId, pairs: 1 } as const)
                    : ({ kind: "global-target" } as const);
            onChange({ ...event, selector: next });
          }}
        >
          <option value="global-target">the system as a whole</option>
          <option value="everyone">everyone of a type</option>
          <option value="random-k">a random few</option>
          <option value="random-pair">random pairs</option>
        </Select>
      </Field>

      {"typeId" in selector ? (
        <Field label="Of type">
          <Select
            className="w-full"
            value={selector.typeId ?? ""}
            onChange={(change) =>
              onChange({ ...event, selector: { ...selector, typeId: change.target.value || null } })
            }
          >
            <option value="">any type</option>
            {objectTypes.map((type) => (
              <option key={type.id} value={type.id}>
                {type.label}
              </option>
            ))}
          </Select>
        </Field>
      ) : null}

      {selector.kind === "random-k" ? (
        <NumberField
          label="How many"
          step={1}
          value={selector.count}
          onChange={(count) => onChange({ ...event, selector: { ...selector, count } })}
        />
      ) : null}
      {selector.kind === "random-pair" ? (
        <NumberField
          label="How many pairs"
          step={1}
          value={selector.pairs}
          onChange={(pairs) => onChange({ ...event, selector: { ...selector, pairs } })}
        />
      ) : null}

      <Row>
        <NumberField
          label="Cooldown (ticks)"
          step={1}
          value={event.cooldownTicks}
          onChange={(cooldownTicks) => onChange({ ...event, cooldownTicks })}
        />
        <NumberField
          label="Max occurrences"
          step={1}
          value={event.maxOccurrences}
          onChange={(maxOccurrences) => onChange({ ...event, maxOccurrences })}
        />
      </Row>
      <p className="text-xs text-[var(--text-muted)]">
        Zero occurrences means no limit. {event.effects.length} effect
        {event.effects.length === 1 ? "" : "s"} attached.
      </p>
    </div>
  );
}

/** A memory trigger: which memories it watches, and what recalling one does to them. */
export function TriggerInspector({
  trigger,
  onChange,
  onDelete,
}: {
  trigger: MemoryTrigger;
  onChange: (trigger: MemoryTrigger) => void;
  onDelete: () => void;
}) {
  const { filter, effect } = trigger;
  return (
    <div className="space-y-3">
      <Header title={trigger.label || trigger.id} onDelete={onDelete} />

      <Field label="Label">
        <Input
          value={trigger.label}
          onChange={(change) => onChange({ ...trigger, label: change.target.value })}
        />
      </Field>

      <div className="text-xs font-medium text-[var(--text-muted)]">Which memories</div>
      <Field label="Kinds" hint="Comma separated; blank means any kind.">
        <Input
          value={filter.kinds.join(", ")}
          onChange={(change) =>
            onChange({ ...trigger, filter: { ...filter, kinds: commaList(change.target.value) } })
          }
        />
      </Field>
      <Row>
        <NumberField
          label="Min strength"
          value={filter.minStrength}
          onChange={(minStrength) => onChange({ ...trigger, filter: { ...filter, minStrength } })}
        />
        <NumberField
          label="Max strength"
          value={filter.maxStrength}
          onChange={(maxStrength) => onChange({ ...trigger, filter: { ...filter, maxStrength } })}
        />
        <NumberField
          label="Min age (ticks)"
          step={1}
          value={filter.minAge}
          onChange={(minAge) => onChange({ ...trigger, filter: { ...filter, minAge } })}
        />
        <NumberField
          label="Max per tick"
          step={1}
          value={trigger.maxPerTick}
          onChange={(maxPerTick) => onChange({ ...trigger, maxPerTick })}
        />
      </Row>

      <div className="text-xs font-medium text-[var(--text-muted)]">What recall does</div>
      <Row>
        <NumberField
          label="Strength boost"
          value={effect.strengthBoost}
          onChange={(strengthBoost) => onChange({ ...trigger, effect: { ...effect, strengthBoost } })}
        />
        <NumberField
          label="Stability gain"
          value={effect.stabilityGain}
          onChange={(stabilityGain) => onChange({ ...trigger, effect: { ...effect, stabilityGain } })}
        />
        <NumberField
          label="Valence shift"
          value={effect.valenceShift}
          onChange={(valenceShift) => onChange({ ...trigger, effect: { ...effect, valenceShift } })}
        />
      </Row>
      <label className="flex items-center gap-2 text-sm text-[var(--text-secondary)]">
        <input
          type="checkbox"
          checked={effect.resetClock}
          onChange={(change) => onChange({ ...trigger, effect: { ...effect, resetClock: change.target.checked } })}
        />
        Reset the forgetting clock on recall
      </label>
      <p className="text-xs text-[var(--text-muted)]">
        A stability gain above one is what produces the spacing effect: each recall makes the next
        one last longer.
      </p>
    </div>
  );
}
