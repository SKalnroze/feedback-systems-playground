import { Badge } from "@/components/ui/primitives";
import type {
  EventNodeData,
  GlobalNodeData,
  ObjectNodeData,
  TriggerNodeData,
} from "@/features/systems/specGraph";
import { cn, formatNumber } from "@/lib/utils";
import { Handle, Position, type NodeProps } from "@xyflow/react";

/**
 * Canvas node types.
 *
 * Variables carry their own connection handles rather than the node as a whole, so a link is drawn
 * between two specific variables. Anything coarser would make the graph a picture of the model
 * rather than the model itself.
 */

const SHELL =
  "min-w-[190px] rounded-lg border bg-[var(--surface-1)] text-[var(--text-primary)] shadow-sm";

const VARIABLE_KIND_LABEL: Record<string, string> = {
  STOCK: "accumulates",
  AUXILIARY: "recomputed",
  CONSTANT: "fixed",
};

export function ObjectNode({ data, selected }: NodeProps) {
  const object = data as unknown as ObjectNodeData;
  return (
    <div
      className={cn(
        SHELL,
        selected ? "border-[var(--accent)]" : "border-[var(--border)]",
      )}
    >
      <div className="border-b border-[var(--border)] px-3 py-1.5">
        <div className="flex items-center gap-1.5">
          <div className="min-w-0 flex-1 truncate text-xs font-semibold">{object.label}</div>
          {/* A population is one node carrying its size. Drawing two thousand nodes would be
              accurate and unreadable, which is not a trade worth making. */}
          {object.count > 1 ? (
            <Badge tone="accent" title={`${object.count.toLocaleString()} instances`}>
              ×{object.count.toLocaleString()}
            </Badge>
          ) : null}
        </div>
        <div className="text-[10px] text-[var(--text-muted)]">
          {object.typeId}
          {object.count > 1 ? " · each with its own memories" : ""}
        </div>
        {object.tags.length > 0 ? (
          <div className="mt-1 flex flex-wrap gap-1">
            {object.tags.map((tag) => (
              <Badge key={tag} tone="neutral">
                {tag}
              </Badge>
            ))}
          </div>
        ) : null}
      </div>
      <div className="py-1">
        {object.variables.map((variable) => {
          const handle = `${object.objectId}.${variable.name}`;
          return (
            <div key={variable.name} className="relative flex items-center justify-between px-3 py-1">
              <Handle
                id={`${handle}::target`}
                type="target"
                position={Position.Left}
                className="!size-2 !border-0 !bg-[var(--border-strong)]"
              />
              <span className="text-[11px]" title={VARIABLE_KIND_LABEL[variable.kind]}>
                {variable.label}
                {variable.kind === "CONSTANT" ? (
                  <span className="ml-1 text-[var(--text-muted)]">·fixed</span>
                ) : null}
              </span>
              <span className="tabular text-[11px] text-[var(--text-muted)]">
                {formatNumber(variable.value, 2)}
              </span>
              <Handle
                id={`${handle}::source`}
                type="source"
                position={Position.Right}
                className="!size-2 !border-0 !bg-[var(--border-strong)]"
              />
            </div>
          );
        })}
        {object.variables.length === 0 ? (
          <p className="px-3 py-1 text-[11px] text-[var(--text-muted)]">
            Its type declares no variables.
          </p>
        ) : null}
      </div>
    </div>
  );
}

export function GlobalNode({ data, selected }: NodeProps) {
  const variable = data as unknown as GlobalNodeData;
  const handle = `global.${variable.name}`;
  return (
    <div
      className={cn(
        SHELL,
        "px-3 py-2",
        selected ? "border-[var(--accent)]" : "border-[var(--border)]",
      )}
    >
      <Handle
        id={`${handle}::target`}
        type="target"
        position={Position.Left}
        className="!size-2 !border-0 !bg-[var(--border-strong)]"
      />
      <div className="text-[10px] uppercase tracking-wide text-[var(--text-muted)]">global</div>
      <div className="text-xs font-semibold">{variable.label}</div>
      <div className="tabular text-[11px] text-[var(--text-muted)]">
        {formatNumber(variable.value, 2)} · {VARIABLE_KIND_LABEL[variable.variableKind]}
      </div>
      <Handle
        id={`${handle}::source`}
        type="source"
        position={Position.Right}
        className="!size-2 !border-0 !bg-[var(--border-strong)]"
      />
    </div>
  );
}

export function EventNode({ data, selected }: NodeProps) {
  const event = data as unknown as EventNodeData;
  return (
    <div
      className={cn(
        SHELL,
        "px-3 py-2",
        selected ? "border-[var(--accent)]" : "border-[var(--border)]",
      )}
    >
      <div className="text-[10px] uppercase tracking-wide text-[var(--text-muted)]">event</div>
      <div className="text-xs font-semibold">{event.label}</div>
      <div className="mt-1 flex flex-wrap gap-1">
        <Badge tone="accent">{event.generator}</Badge>
        <Badge tone="neutral">{event.selector}</Badge>
        <Badge tone="neutral">
          {event.effectCount} {event.effectCount === 1 ? "effect" : "effects"}
        </Badge>
      </div>
    </div>
  );
}

export function TriggerNode({ data, selected }: NodeProps) {
  const trigger = data as unknown as TriggerNodeData;
  return (
    <div
      className={cn(
        SHELL,
        "px-3 py-2",
        selected ? "border-[var(--accent)]" : "border-[var(--border)]",
      )}
    >
      <div className="text-[10px] uppercase tracking-wide text-[var(--text-muted)]">
        memory trigger
      </div>
      <div className="text-xs font-semibold">{trigger.label}</div>
      <Badge tone="warning" className="mt-1">
        {trigger.condition}
      </Badge>
    </div>
  );
}

export const NODE_TYPES = {
  objectNode: ObjectNode,
  globalNode: GlobalNode,
  eventNode: EventNode,
  triggerNode: TriggerNode,
};
