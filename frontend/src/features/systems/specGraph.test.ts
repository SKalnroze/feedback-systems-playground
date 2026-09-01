import type { SystemSpec } from "@/api/types";
import { DECAY_PRESETS } from "@/features/systems/decayCurve";
import {
  emptySpec,
  handleId,
  newLink,
  nodeIdForHandle,
  refFromHandle,
  specToGraph,
} from "@/features/systems/specGraph";
import { describe, expect, it } from "vitest";

function spec(): SystemSpec {
  return {
    ...emptySpec("test"),
    objectTypes: [
      {
        id: "person",
        label: "Person",
        variables: [
          { name: "trust", label: "trust", kind: "STOCK", initial: 0.5, min: 0, max: 1 },
          { name: "resentment", label: "resentment", kind: "STOCK", initial: 0, min: 0, max: 1 },
        ],
        memory: {
          defaultDecay: DECAY_PRESETS.exponential,
          retrievalThreshold: 0.05,
          capacity: 0,
          pruneForgotten: false,
          similarityThreshold: 0.6,
        },
        defaultTags: [],
        defaultFeatures: {},
      },
    ],
    objects: [
      { id: "ana", typeId: "person", label: "ana", variables: { trust: 0.8 }, tags: ["lead"], features: {}, count: 1 },
      { id: "ben", typeId: "person", label: "ben", variables: {}, tags: [], features: {}, count: 1 },
    ],
    globalVariables: [
      { name: "tension", label: "tension", kind: "AUXILIARY", initial: 0, min: 0, max: 1 },
    ],
    links: [
      {
        id: "a-to-b",
        label: "a-to-b",
        source: { kind: "object", objectId: "ana", name: "resentment" },
        target: { kind: "object", objectId: "ben", name: "trust" },
        gain: -0.2,
        delayTicks: 3,
        transfer: { kind: "transfer-linear" },
        usesRate: false,
      coupling: { mode: "AUTO", aggregate: "MEAN" },
      },
    ],
  };
}

describe("handles", () => {
  it("round-trips a variable reference", () => {
    for (const ref of [
      { kind: "global", name: "tension" },
      { kind: "object", objectId: "ana", name: "trust" },
    ] as const) {
      expect(refFromHandle(handleId(ref))).toEqual(ref);
    }
  });

  it("round-trips a type aggregate", () => {
    const ref = { kind: "type-aggregate", typeId: "person", name: "trust", aggregate: "MEAN" } as const;

    expect(refFromHandle(handleId(ref))).toEqual(ref);
  });

  it("names the node that owns a handle", () => {
    expect(nodeIdForHandle("ana.trust")).toBe("object:ana");
    expect(nodeIdForHandle("global.tension")).toBe("globals");
  });

  it("rejects a handle that names nothing", () => {
    expect(refFromHandle("nonsense")).toBeNull();
  });
});

describe("spec to graph", () => {
  it("draws a node for every object, global and event", () => {
    const { nodes } = specToGraph(spec());

    expect(nodes.map((node) => node.id)).toEqual(
      expect.arrayContaining(["object:ana", "object:ben", "global:tension"]),
    );
  });

  it("gives an object node its variables and current values", () => {
    const { nodes } = specToGraph(spec());
    const ana = nodes.find((node) => node.id === "object:ana");

    expect(ana?.data).toMatchObject({ kind: "object", objectId: "ana", tags: ["lead"] });
    const variables = (ana?.data as { variables: { name: string; value: number }[] }).variables;
    // The object overrides trust; resentment falls back to its type's initial value.
    expect(variables).toEqual([
      expect.objectContaining({ name: "trust", value: 0.8 }),
      expect.objectContaining({ name: "resentment", value: 0 }),
    ]);
  });

  it("draws a link as an edge between the two variables it connects", () => {
    const { edges } = specToGraph(spec());

    expect(edges).toHaveLength(1);
    expect(edges[0]).toMatchObject({
      id: "a-to-b",
      source: "object:ana",
      target: "object:ben",
      sourceHandle: "ana.resentment::source",
      targetHandle: "ben.trust::target",
    });
  });

  it("marks a negative, delayed link so its nature is visible without reading the number", () => {
    const { edges } = specToGraph(spec());

    expect(edges[0]?.label).toBe("−0.2 ·3t");
    expect(edges[0]?.animated).toBe(true);
  });
});

describe("new links", () => {
  it("names a link after what it connects", () => {
    const link = newLink(
      { kind: "object", objectId: "ana", name: "trust" },
      { kind: "global", name: "tension" },
      [],
    );

    expect(link.id).toBe("ana-trust-global-tension");
    expect(link.gain).toBeGreaterThan(0);
    expect(link.delayTicks).toBe(0);
  });

  it("avoids colliding with an existing link", () => {
    const source = { kind: "object", objectId: "ana", name: "trust" } as const;
    const target = { kind: "global", name: "tension" } as const;
    const first = newLink(source, target, []);
    const second = newLink(source, target, [first.id]);

    expect(second.id).not.toBe(first.id);
  });
});
