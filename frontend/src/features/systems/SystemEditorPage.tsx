import { api } from "@/api/client";
import { useDraftValidation, usePalette, usePublish, useSaveDraft, useSystem, useVersions } from "@/api/queries";
import type { LinkSpec, ObjectTypeSpec, SystemSpec } from "@/api/types";
import {
  Badge,
  Button,
  Card,
  ErrorNote,
  Field,
  Input,
  Select,
  Spinner,
} from "@/components/ui/primitives";
import { DecayInspector, LinkInspector, LoopSummary } from "@/features/systems/Inspector";
import { NODE_TYPES } from "@/features/systems/nodes";
import {
  handleId,
  newLink,
  refFromHandle,
  specToGraph,
  type SpecNode,
} from "@/features/systems/specGraph";
import { cn } from "@/lib/utils";
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  type Connection,
  type Edge,
} from "@xyflow/react";
import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

/**
 * The visual editor.
 *
 * The canvas shows the model as it actually is - objects with their variables, the links between
 * them, the events and triggers acting on them - and dragging between two variables creates a real
 * link in the specification. Validation runs continuously against the saved draft, so problems and
 * the loops the graph contains are visible while building rather than at publish time.
 */
export function SystemEditorPage() {
  const { systemId } = useParams({ from: "/systems/$systemId" });
  return (
    <ReactFlowProvider>
      <EditorInner systemId={systemId} />
    </ReactFlowProvider>
  );
}

function EditorInner({ systemId }: { systemId: string }) {
  const system = useSystem(systemId);
  const versions = useVersions(systemId);
  const palette = usePalette();
  const saveDraft = useSaveDraft(systemId);
  const publish = usePublish(systemId);
  const validation = useDraftValidation(systemId);
  const navigate = useNavigate();

  const [spec, setSpec] = useState<SystemSpec | null>(null);
  const [selection, setSelection] = useState<{ kind: "link"; id: string } | { kind: "type"; id: string } | null>(
    null,
  );
  const [publishError, setPublishError] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    if (system.data?.draft && !spec) setSpec(system.data.draft);
  }, [system.data?.draft, spec]);

  const graph = useMemo(() => (spec ? specToGraph(spec) : { nodes: [], edges: [] }), [spec]);
  const [nodes, setNodes, onNodesChange] = useNodesState<SpecNode>(graph.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>(graph.edges);

  useEffect(() => {
    setNodes(graph.nodes);
    setEdges(graph.edges);
  }, [graph, setNodes, setEdges]);

  // Autosave, debounced. The editor is a live view of a draft; making the user press save would
  // add a way to lose work without adding any safety, since nothing is published automatically.
  const saveTimer = useRef<number | null>(null);
  useEffect(() => {
    if (!spec || !dirty) return undefined;
    if (saveTimer.current) window.clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => {
      saveDraft.mutate(
        { name: spec.name, description: spec.description, draft: spec },
        { onSuccess: () => setDirty(false) },
      );
    }, 600);
    return () => {
      if (saveTimer.current) window.clearTimeout(saveTimer.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [spec, dirty]);

  const update = useCallback((mutate: (current: SystemSpec) => SystemSpec) => {
    setSpec((current) => (current ? mutate(current) : current));
    setDirty(true);
  }, []);

  const onConnect = useCallback(
    (connection: Connection) => {
      if (!connection.sourceHandle || !connection.targetHandle) return;
      const source = refFromHandle(connection.sourceHandle.replace("::source", ""));
      const target = refFromHandle(connection.targetHandle.replace("::target", ""));
      if (!source || !target) return;
      update((current) => ({
        ...current,
        links: [...current.links, newLink(source, target, current.links.map((link) => link.id))],
      }));
    },
    [update],
  );

  const selectedLink = useMemo(
    () => (selection?.kind === "link" ? (spec?.links.find((link) => link.id === selection.id) ?? null) : null),
    [selection, spec],
  );

  const selectedType = useMemo(
    () =>
      selection?.kind === "type"
        ? (spec?.objectTypes.find((type) => type.id === selection.id) ?? null)
        : null,
    [selection, spec],
  );

  if (system.isLoading || !spec) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-sm text-[var(--text-muted)]">
        <Spinner /> Loading system…
      </div>
    );
  }

  const report = validation.data;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center gap-3 border-b border-[var(--border)] px-4 py-2">
        <Link to="/systems" className="text-xs text-[var(--text-muted)] hover:underline">
          ← Systems
        </Link>
        <input
          className="min-w-[180px] bg-transparent text-sm font-semibold outline-none"
          value={spec.name}
          onChange={(event) => update((current) => ({ ...current, name: event.target.value }))}
          aria-label="System name"
        />

        <span className="text-xs text-[var(--text-muted)]">
          {dirty || saveDraft.isPending ? "saving…" : "saved"}
        </span>

        {report ? (
          report.valid ? (
            <Badge tone="good">valid</Badge>
          ) : (
            <Badge tone="critical">
              {report.issues.filter((issue) => issue.severity === "ERROR").length} problems
            </Badge>
          )
        ) : null}

        {versions.data && versions.data.length > 0 ? (
          <Badge tone="neutral">v{versions.data[0]?.version} published</Badge>
        ) : null}

        <div className="ml-auto flex items-center gap-2">
          <Button
            variant="primary"
            disabled={publish.isPending || dirty}
            title={dirty ? "Wait for the draft to save" : "Publish an immutable version"}
            onClick={async () => {
              setPublishError(null);
              try {
                await publish.mutateAsync();
              } catch (cause) {
                setPublishError(cause instanceof Error ? cause.message : "Could not publish.");
              }
            }}
          >
            Publish
          </Button>
          <Button
            disabled={!versions.data?.length}
            onClick={async () => {
              const latest = await api.latestVersion(systemId);
              const run = await api.createRun({
                systemVersionId: latest.id,
                autoStart: true,
                speed: 10,
              });
              void navigate({ to: "/runs/$runId", params: { runId: run.id } });
            }}
          >
            Publish &amp; run
          </Button>
        </div>
      </div>

      {publishError ? (
        <div className="px-4 pt-2">
          <ErrorNote message={publishError} />
        </div>
      ) : null}

      <div className="grid min-h-0 flex-1 grid-cols-[220px_1fr_320px]">
        <aside className="min-h-0 overflow-auto border-r border-[var(--border)] p-3">
          <PalettePanel
            spec={spec}
            objectTypes={palette.data?.objectTypes ?? []}
            rules={palette.data?.rules ?? []}
            onUpdate={update}
            onSelectType={(id) => setSelection({ kind: "type", id })}
          />
        </aside>

        <div className="min-h-0">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={NODE_TYPES}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onEdgeClick={(_, edge) => setSelection({ kind: "link", id: edge.id })}
            fitView
            proOptions={{ hideAttribution: false }}
            defaultEdgeOptions={{ type: "smoothstep" }}
          >
            <Background color="var(--border)" gap={18} size={1} />
            <Controls className="!bg-[var(--surface-1)] !text-[var(--text-primary)]" />
            <MiniMap
              pannable
              zoomable
              className="!bg-[var(--surface-2)]"
              maskColor="color-mix(in srgb, var(--surface-0) 70%, transparent)"
              nodeColor="var(--border-strong)"
            />
          </ReactFlow>
        </div>

        <aside className="min-h-0 overflow-auto border-l border-[var(--border)] p-3">
          {selectedLink ? (
            <LinkInspector
              link={selectedLink}
              onChange={(link: LinkSpec) =>
                update((current) => ({
                  ...current,
                  links: current.links.map((item) => (item.id === link.id ? link : item)),
                }))
              }
              onDelete={() => {
                update((current) => ({
                  ...current,
                  links: current.links.filter((item) => item.id !== selectedLink.id),
                }));
                setSelection(null);
              }}
            />
          ) : selectedType ? (
            <div className="space-y-3">
              <h3 className="text-sm font-semibold">{selectedType.label} memory</h3>
              <DecayInspector
                model={selectedType.memory.defaultDecay}
                onChange={(model) =>
                  update((current) => ({
                    ...current,
                    objectTypes: current.objectTypes.map((type) =>
                      type.id === selectedType.id
                        ? { ...type, memory: { ...type.memory, defaultDecay: model } }
                        : type,
                    ),
                  }))
                }
              />
              <MemorySettingsFields
                type={selectedType}
                onChange={(next) =>
                  update((current) => ({
                    ...current,
                    objectTypes: current.objectTypes.map((type) =>
                      type.id === selectedType.id ? next : type,
                    ),
                  }))
                }
              />
            </div>
          ) : (
            <div className="space-y-4">
              <div>
                <h3 className="mb-1.5 text-sm font-semibold">Feedback loops</h3>
                <LoopSummary report={report?.loops ?? []} />
              </div>

              {report && report.issues.length > 0 ? (
                <div>
                  <h3 className="mb-1.5 text-sm font-semibold">Problems</h3>
                  <ul className="space-y-1 text-xs">
                    {report.issues.map((issue, index) => (
                      <li
                        key={index}
                        className={cn(
                          "rounded border px-2 py-1",
                          issue.severity === "ERROR"
                            ? "border-[var(--status-critical)]/40 text-[var(--status-critical)]"
                            : "border-[var(--status-warning)]/40 text-[var(--status-warning)]",
                        )}
                      >
                        <span className="font-medium">{issue.elementId}</span> — {issue.message}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}

              {report && report.missingRules.length > 0 ? (
                <ErrorNote
                  message={`This system uses behaviours no installed module provides: ${report.missingRules.join(", ")}`}
                />
              ) : null}

              <p className="text-xs text-[var(--text-muted)]">
                Select a link to edit its strength, delay and shape, or an object type in the palette
                to change how its memories fade.
              </p>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}

function MemorySettingsFields({
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

/** Left rail: what can be added, and what is already in the system. */
function PalettePanel({
  spec,
  objectTypes,
  rules,
  onUpdate,
  onSelectType,
}: {
  spec: SystemSpec;
  objectTypes: ObjectTypeSpec[];
  rules: { id: string; label: string; description: string }[];
  onUpdate: (mutate: (current: SystemSpec) => SystemSpec) => void;
  onSelectType: (id: string) => void;
}) {
  const [newObjectType, setNewObjectType] = useState("");

  const availableTypes = useMemo(() => {
    const seen = new Set(spec.objectTypes.map((type) => type.id));
    return [...spec.objectTypes, ...objectTypes.filter((type) => !seen.has(type.id))];
  }, [spec.objectTypes, objectTypes]);

  return (
    <div className="space-y-4">
      <div>
        <h3 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-[var(--text-muted)]">
          Object types
        </h3>
        <ul className="space-y-1">
          {spec.objectTypes.map((type) => (
            <li key={type.id}>
              <button
                type="button"
                onClick={() => onSelectType(type.id)}
                className="w-full rounded border border-[var(--border)] px-2 py-1 text-left text-xs hover:bg-[var(--surface-2)]"
              >
                {type.label}
                <span className="block text-[10px] text-[var(--text-muted)]">
                  {type.variables.length} variables · {type.memory.defaultDecay.kind}
                </span>
              </button>
            </li>
          ))}
        </ul>

        <div className="mt-2 flex gap-1">
          <Select
            className="h-7 min-w-0 flex-1 text-xs"
            value={newObjectType}
            onChange={(event) => setNewObjectType(event.target.value)}
          >
            <option value="">add type…</option>
            {availableTypes
              .filter((type) => !spec.objectTypes.some((existing) => existing.id === type.id))
              .map((type) => (
                <option key={type.id} value={type.id}>
                  {type.label}
                </option>
              ))}
          </Select>
          <Button
            size="sm"
            disabled={!newObjectType}
            onClick={() => {
              const type = objectTypes.find((candidate) => candidate.id === newObjectType);
              if (!type) return;
              onUpdate((current) => ({ ...current, objectTypes: [...current.objectTypes, type] }));
              setNewObjectType("");
            }}
          >
            +
          </Button>
        </div>
      </div>

      <div>
        <h3 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-[var(--text-muted)]">
          Objects
        </h3>
        <Button
          size="sm"
          className="w-full"
          disabled={spec.objectTypes.length === 0}
          onClick={() =>
            onUpdate((current) => {
              const type = current.objectTypes[0];
              if (!type) return current;
              const id = `${type.id}-${current.objects.length + 1}`;
              return {
                ...current,
                objects: [
                  ...current.objects,
                  { id, typeId: type.id, label: id, variables: {}, tags: [], features: {} },
                ],
              };
            })
          }
        >
          + Add object
        </Button>
        {spec.objectTypes.length === 0 ? (
          <p className="mt-1 text-[11px] text-[var(--text-muted)]">Add an object type first.</p>
        ) : null}
      </div>

      <div>
        <h3 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-[var(--text-muted)]">
          Global variables
        </h3>
        <Button
          size="sm"
          className="w-full"
          onClick={() =>
            onUpdate((current) => {
              const name = `global${current.globalVariables.length + 1}`;
              return {
                ...current,
                globalVariables: [
                  ...current.globalVariables,
                  { name, label: name, kind: "STOCK", initial: 0, min: 0, max: 1 },
                ],
              };
            })
          }
        >
          + Add global
        </Button>
      </div>

      <div>
        <h3 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-[var(--text-muted)]">
          Behaviours
        </h3>
        <ul className="space-y-1">
          {rules.map((rule) => {
            const enabled = spec.interactions.some(
              (config) => config.ruleId === rule.id && config.enabled,
            );
            return (
              <li key={rule.id}>
                <label
                  className="flex cursor-pointer items-start gap-2 rounded px-1 py-1 text-xs hover:bg-[var(--surface-2)]"
                  title={rule.description}
                >
                  <input
                    type="checkbox"
                    checked={enabled}
                    onChange={(event) =>
                      onUpdate((current) => ({
                        ...current,
                        interactions: event.target.checked
                          ? [
                              ...current.interactions.filter((config) => config.ruleId !== rule.id),
                              { ruleId: rule.id, enabled: true, weight: 1, params: {} },
                            ]
                          : current.interactions.filter((config) => config.ruleId !== rule.id),
                      }))
                    }
                  />
                  <span>
                    {rule.label}
                    <span className="block text-[10px] text-[var(--text-muted)]">
                      {rule.description}
                    </span>
                  </span>
                </label>
              </li>
            );
          })}
        </ul>
      </div>

      <Card className="p-2">
        <p className="text-[11px] text-[var(--text-muted)]">
          Drag from a variable on the right of one box to a variable on the left of another to make
          a link. Dashed links are delayed.
        </p>
      </Card>
    </div>
  );
}

export { handleId };
