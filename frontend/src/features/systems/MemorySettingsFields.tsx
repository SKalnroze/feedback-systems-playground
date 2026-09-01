import type { ObjectTypeSpec } from "@/api/types";
import { Field, Input } from "@/components/ui/primitives";

/**
 * The non-curve half of a type's memory settings.
 *
 * Lifted out of the system editor so the standalone type editor uses the same fields rather than
 * a second set that drifts away from them.
 */
export function MemorySettingsFields({
  type,
  onChange,
}: {
  type: ObjectTypeSpec;
  onChange: (type: ObjectTypeSpec) => void;
}) {
  const memory = type.memory;
  return (
    <div className="space-y-2">
      <div className="grid grid-cols-2 gap-2">
        <Field label="Retrieval threshold">
          <Input
            className="tabular"
            type="number"
            step="0.01"
            value={memory.retrievalThreshold}
            onChange={(event) =>
              onChange({
                ...type,
                memory: { ...memory, retrievalThreshold: Number(event.target.value) },
              })
            }
          />
        </Field>
        <Field label="Capacity" hint="0 for unlimited">
          <Input
            className="tabular"
            type="number"
            value={memory.capacity}
            onChange={(event) =>
              onChange({ ...type, memory: { ...memory, capacity: Number(event.target.value) } })
            }
          />
        </Field>
      </div>
      <label className="flex items-center gap-2 text-xs text-[var(--text-secondary)]">
        <input
          type="checkbox"
          checked={memory.pruneForgotten}
          onChange={(event) =>
            onChange({ ...type, memory: { ...memory, pruneForgotten: event.target.checked } })
          }
        />
        Discard memories once they fall below the threshold
      </label>
    </div>
  );
}
