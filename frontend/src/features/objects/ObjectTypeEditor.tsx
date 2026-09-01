import type { ObjectTypeSpec, VariableSpec } from "@/api/types";
import { DecayInspector } from "@/features/systems/Inspector";
import { MemorySettingsFields } from "@/features/systems/MemorySettingsFields";
import { Button, Field, Input, Select } from "@/components/ui/primitives";
import { ConfirmButton } from "@/components/ui/ConfirmButton";

const KINDS: VariableSpec["kind"][] = ["STOCK", "AUXILIARY", "CONSTANT"];

/** What each kind does, shown where the choice is made rather than in documentation nobody opens. */
const KIND_HINT: Record<VariableSpec["kind"], string> = {
  STOCK: "Accumulates. Links add to it and it keeps what it has — trust, resentment, fatigue.",
  AUXILIARY: "Recomputed every tick from what arrives. Holds no history of its own.",
  CONSTANT: "Never written by a link. A property of the object rather than a state it is in.",
};

/**
 * Editing what a kind of object is made of.
 *
 * Until now the vocabulary was sealed: an author could use the types a module shipped and nothing
 * else, so any system the module packs had not anticipated could not be built at all.
 *
 * The same component serves the standalone template editor and the dialog inside the system editor,
 * because they are the same task — the only difference is where the result is saved, and that
 * belongs to the caller.
 */
export function ObjectTypeEditor({
  type,
  onChange,
  idEditable = true,
}: {
  type: ObjectTypeSpec;
  onChange: (type: ObjectTypeSpec) => void;
  /** False once instances exist, since renaming a type would orphan every one of them. */
  idEditable?: boolean;
}) {
  function setVariable(index: number, next: VariableSpec) {
    onChange({ ...type, variables: type.variables.map((item, at) => (at === index ? next : item)) });
  }

  function addVariable() {
    // Named off the count rather than "new", so adding three in a row does not produce three
    // variables that cannot be told apart.
    const name = `variable${type.variables.length + 1}`;
    onChange({
      ...type,
      variables: [
        ...type.variables,
        { name, label: name, kind: "STOCK", initial: 0.5, min: 0, max: 1 },
      ],
    });
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3">
        <Field label="Id" hint={idEditable ? "Used by links and rules" : "Fixed once objects exist"}>
          <Input
            value={type.id}
            disabled={!idEditable}
            onChange={(event) =>
              onChange({ ...type, id: event.target.value.replace(/[^a-zA-Z0-9_-]/g, "") })
            }
          />
        </Field>
        <Field label="Label">
          <Input value={type.label} onChange={(event) => onChange({ ...type, label: event.target.value })} />
        </Field>
      </div>

      <div>
        <div className="mb-1.5 flex items-center justify-between">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-[var(--text-muted)]">
            Variables
          </h3>
          <Button size="sm" onClick={addVariable}>
            + Add variable
          </Button>
        </div>

        {type.variables.length === 0 ? (
          <p className="rounded border border-dashed border-[var(--border)] px-3 py-4 text-center text-xs text-[var(--text-muted)]">
            No variables yet. An object type with none can still be linked to, but has nothing to move.
          </p>
        ) : (
          <div className="space-y-2">
            {type.variables.map((variable, index) => (
              <div
                key={index}
                className="grid grid-cols-[1fr_1fr_auto] items-end gap-2 rounded border border-[var(--border)] p-2"
              >
                <Field label="Name">
                  <Input
                    className="text-xs"
                    value={variable.name}
                    onChange={(event) =>
                      setVariable(index, {
                        ...variable,
                        name: event.target.value.replace(/[^a-zA-Z0-9_]/g, ""),
                      })
                    }
                  />
                </Field>
                <Field label="Label">
                  <Input
                    className="text-xs"
                    value={variable.label}
                    onChange={(event) => setVariable(index, { ...variable, label: event.target.value })}
                  />
                </Field>
                <ConfirmButton
                  onConfirm={() =>
                    onChange({ ...type, variables: type.variables.filter((_, at) => at !== index) })
                  }
                  title="Remove this variable from the type"
                />

                <Field label="Kind" hint={KIND_HINT[variable.kind]}>
                  <Select
                    className="w-full text-xs"
                    value={variable.kind}
                    onChange={(event) =>
                      setVariable(index, { ...variable, kind: event.target.value as VariableSpec["kind"] })
                    }
                  >
                    {KINDS.map((kind) => (
                      <option key={kind} value={kind}>
                        {kind.toLowerCase()}
                      </option>
                    ))}
                  </Select>
                </Field>

                <div className="col-span-2 grid grid-cols-3 gap-2">
                  <Field label="Initial">
                    <Input
                      className="tabular text-xs"
                      type="number"
                      step="0.05"
                      value={variable.initial}
                      onChange={(event) =>
                        setVariable(index, { ...variable, initial: Number(event.target.value) })
                      }
                    />
                  </Field>
                  <Field label="Min">
                    <Input
                      className="tabular text-xs"
                      type="number"
                      step="0.5"
                      value={variable.min}
                      onChange={(event) => setVariable(index, { ...variable, min: Number(event.target.value) })}
                    />
                  </Field>
                  <Field label="Max">
                    <Input
                      className="tabular text-xs"
                      type="number"
                      step="0.5"
                      value={variable.max}
                      onChange={(event) => setVariable(index, { ...variable, max: Number(event.target.value) })}
                    />
                  </Field>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <div>
        <h3 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-[var(--text-muted)]">
          How instances remember
        </h3>
        <div className="space-y-3">
          <DecayInspector
            model={type.memory.defaultDecay}
            onChange={(model) => onChange({ ...type, memory: { ...type.memory, defaultDecay: model } })}
          />
          <MemorySettingsFields type={type} onChange={onChange} />
        </div>
      </div>

      <Field label="Default tags" hint="Comma separated; every instance starts with these.">
        <Input
          value={type.defaultTags.join(", ")}
          onChange={(event) =>
            onChange({
              ...type,
              defaultTags: event.target.value
                .split(",")
                .map((tag) => tag.trim())
                .filter(Boolean),
            })
          }
        />
      </Field>
    </div>
  );
}

/** A blank type, with one variable so the editor opens on something rather than nothing. */
export function emptyObjectType(id = "thing"): ObjectTypeSpec {
  return {
    id,
    label: id,
    variables: [{ name: "level", label: "level", kind: "STOCK", initial: 0.5, min: 0, max: 1 }],
    memory: {
      defaultDecay: { kind: "exponential", halfLife: 50, common: { floor: 0, interference: 0 } },
      retrievalThreshold: 0.05,
      capacity: 0,
      pruneForgotten: false,
      similarityThreshold: 0.6,
    },
    defaultTags: [],
    defaultFeatures: {},
    templateId: null,
    templateVersion: null,
  };
}
