import type { LinkSpec, SystemSpec, VariableRef } from "@/api/types";
import type { Edge, Node } from "@xyflow/react";

/**
 * Translation between a system specification and the graph the editor draws.
 *
 * The spec is the source of truth in both directions - the canvas is a view of it, not a parallel
 * model - so every edit round-trips through here rather than accumulating editor-only state that
 * could drift from what actually runs.
 */

export type ObjectNodeData = {
  kind: "object";
  objectId: string;
  label: string;
  typeId: string;
  variables: { name: string; label: string; value: number; kind: string }[];
  tags: string[];
};

export type GlobalNodeData = {
  kind: "global";
  name: string;
  label: string;
  value: number;
  variableKind: string;
};

export type EventNodeData = {
  kind: "event";
  eventId: string;
  label: string;
  generator: string;
  selector: string;
  effectCount: number;
};

export type TriggerNodeData = {
  kind: "trigger";
  triggerId: string;
  label: string;
  condition: string;
};

export type SpecNodeData = ObjectNodeData | GlobalNodeData | EventNodeData | TriggerNodeData;
export type SpecNode = Node<SpecNodeData & Record<string, unknown>>;

/** Handle id for a variable, which is what a link connects to. */
export function handleId(ref: VariableRef): string {
  switch (ref.kind) {
    case "global":
      return `global.${ref.name}`;
    case "object":
      return `${ref.objectId}.${ref.name}`;
    case "type-aggregate":
      return `type:${ref.typeId}.${ref.name}`;
  }
}

/** Turns a handle id back into the reference it names. */
export function refFromHandle(handle: string): VariableRef | null {
  if (handle.startsWith("type:")) {
    const [typeId, name] = handle.slice(5).split(".");
    return typeId && name ? { kind: "type-aggregate", typeId, name, aggregate: "MEAN" } : null;
  }
  const separator = handle.indexOf(".");
  if (separator < 0) return null;
  const owner = handle.slice(0, separator);
  const name = handle.slice(separator + 1);
  if (owner === "global") return { kind: "global", name };
  return { kind: "object", objectId: owner, name };
}

/** Node id that owns a handle, so an edge can be attached to the right box. */
export function nodeIdForHandle(handle: string): string {
  if (handle.startsWith("type:")) return `type:${handle.slice(5).split(".")[0]}`;
  const owner = handle.slice(0, handle.indexOf("."));
  return owner === "global" ? "globals" : `object:${owner}`;
}

/**
 * Builds the canvas from a spec.
 *
 * Positions are laid out in columns by node kind. A stored layout would be nicer, but the spec is
 * the artefact that gets versioned and run, and putting pixel coordinates in it would mean every
 * drag produced a new version of the model.
 */
export function specToGraph(spec: SystemSpec): { nodes: SpecNode[]; edges: Edge[] } {
  const nodes: SpecNode[] = [];

  if (spec.globalVariables.length > 0) {
    spec.globalVariables.forEach((variable, index) => {
      nodes.push({
        id: `global:${variable.name}`,
        type: "globalNode",
        position: { x: 0, y: index * 110 },
        data: {
          kind: "global",
          name: variable.name,
          label: variable.label,
          value: variable.initial,
          variableKind: variable.kind,
        },
      });
    });
  }

  spec.objects.forEach((object, index) => {
    const type = spec.objectTypes.find((candidate) => candidate.id === object.typeId);
    nodes.push({
      id: `object:${object.id}`,
      type: "objectNode",
      position: { x: 320, y: index * 210 },
      data: {
        kind: "object",
        objectId: object.id,
        label: object.label,
        typeId: object.typeId,
        tags: object.tags,
        variables: (type?.variables ?? []).map((variable) => ({
          name: variable.name,
          label: variable.label,
          value: object.variables[variable.name] ?? variable.initial,
          kind: variable.kind,
        })),
      },
    });
  });

  spec.events.forEach((event, index) => {
    nodes.push({
      id: `event:${event.id}`,
      type: "eventNode",
      position: { x: 700, y: index * 130 },
      data: {
        kind: "event",
        eventId: event.id,
        label: event.label,
        generator: event.generator.kind,
        selector: event.selector.kind,
        effectCount: event.effects.length,
      },
    });
  });

  spec.triggers.forEach((trigger, index) => {
    nodes.push({
      id: `trigger:${trigger.id}`,
      type: "triggerNode",
      position: { x: 1000, y: index * 130 },
      data: {
        kind: "trigger",
        triggerId: trigger.id,
        label: trigger.label,
        condition: trigger.condition.kind,
      },
    });
  });

  const edges: Edge[] = spec.links.map((link) => ({
    id: link.id,
    source: nodeIdForHandle(handleId(link.source)),
    target: nodeIdForHandle(handleId(link.target)),
    sourceHandle: `${handleId(link.source)}::source`,
    targetHandle: `${handleId(link.target)}::target`,
    label: linkLabel(link),
    animated: link.delayTicks > 0,
    style: {
      // Sign is carried by colour and by the label, never by colour alone.
      stroke: link.gain < 0 ? "var(--diverging-negative)" : "var(--diverging-positive)",
      strokeWidth: 2,
      strokeDasharray: link.delayTicks > 0 ? "6 4" : undefined,
    },
    labelStyle: { fill: "var(--text-secondary)", fontSize: 10 },
    labelBgStyle: { fill: "var(--surface-1)" },
  }));

  return { nodes, edges };
}

function linkLabel(link: LinkSpec): string {
  const sign = link.gain < 0 ? "−" : "+";
  const delay = link.delayTicks > 0 ? ` ·${link.delayTicks}t` : "";
  return `${sign}${Math.abs(link.gain)}${delay}`;
}

/** A fresh link between two variables, with a generated id. */
export function newLink(source: VariableRef, target: VariableRef, existingIds: string[]): LinkSpec {
  const base = `${handleId(source)}->${handleId(target)}`.replace(/[^a-zA-Z0-9]+/g, "-");
  let id = base;
  let suffix = 2;
  while (existingIds.includes(id)) id = `${base}-${suffix++}`;
  return {
    id,
    label: id,
    source,
    target,
    gain: 0.1,
    delayTicks: 0,
    transfer: { kind: "transfer-linear" },
    usesRate: false,
  };
}

/** An empty system, used when creating one from scratch rather than from a preset. */
export function emptySpec(name: string): SystemSpec {
  return {
    id: name.toLowerCase().replace(/[^a-z0-9]+/g, "-") || "system",
    name,
    description: "",
    moduleIds: [],
    objectTypes: [],
    objects: [],
    globalVariables: [],
    links: [],
    events: [],
    triggers: [],
    interactions: [],
    settings: {
      metricSampleInterval: 1,
      autoCheckpointInterval: 500,
      maxTicks: 0,
      interactionsPerTick: 1,
      sampleMemoryStrength: false,
    },
  };
}
