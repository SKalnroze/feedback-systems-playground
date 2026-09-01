import { useCreateFromPreset, useCreateSystem, useDeleteSystem, useDescribeSystem, usePresets, useSystems } from "@/api/queries";
import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  ErrorNote,
  Field,
  Input,
  Skeleton,
  Spinner,
} from "@/components/ui/primitives";
import { emptySpec } from "@/features/systems/specGraph";
import { ConfirmButton } from "@/components/ui/ConfirmButton";
import { EditableDescription } from "@/components/ui/EditableDescription";
import { formatRelativeTime } from "@/lib/utils";
import { Link, useNavigate } from "@tanstack/react-router";
import { useState } from "react";

/** Systems you have defined, and the presets you can start from. */
export function SystemsPage() {
  const systems = useSystems();
  const saveDescription = useDescribeSystem();
  const presets = usePresets();
  const createSystem = useCreateSystem();
  const fromPreset = useCreateFromPreset();
  const deleteSystem = useDeleteSystem();
  const navigate = useNavigate();

  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function create() {
    setError(null);
    try {
      const created = await createSystem.mutateAsync({
        name: name || "Untitled system",
        draft: emptySpec(name || "Untitled system"),
      });
      setName("");
      void navigate({ to: "/systems/$systemId", params: { systemId: created.id } });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not create the system.");
    }
  }

  return (
    <div className="mx-auto grid max-w-6xl gap-4 p-4 lg:grid-cols-[1fr_340px]">
      <Card className="min-w-0">
        <CardHeader
          title="Systems"
          subtitle="Each is edited as a draft, then published as a version runs can point at."
          actions={systems.isFetching ? <Spinner /> : null}
        />
        {systems.isLoading ? (
          <Skeleton rows={4} />
        ) : systems.data && systems.data.length > 0 ? (
          <ul className="divide-y divide-[var(--border)]">
            {systems.data.map((system) => (
              <li key={system.id} className="flex items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <Link
                    to="/systems/$systemId"
                    params={{ systemId: system.id }}
                    className="truncate text-sm font-medium hover:underline"
                  >
                    {system.name}
                  </Link>
                  <div className="truncate text-[11px] text-[var(--text-muted)]">
                    edited {formatRelativeTime(system.updatedAt)}
                  </div>
                  <EditableDescription
                    value={system.description}
                    placeholder="What does this system model?"
                    onSave={(description) =>
                      saveDescription.mutate({
                        id: system.id,
                        name: system.name,
                        description,
                        draft: system.draft,
                      })
                    }
                  />
                </div>
                {system.draft ? (
                  <Badge tone="neutral">
                    {system.draft.objects.length} objects · {system.draft.links.length} links
                  </Badge>
                ) : (
                  <Badge tone="warning">no draft</Badge>
                )}
                <ConfirmButton
                  onConfirm={() => deleteSystem.mutate(system.id)}
                  title="Delete this system and its published versions"
                />
              </li>
            ))}
          </ul>
        ) : (
          <EmptyState
            title="No systems yet"
            description="Start from a preset to see a working model, or create an empty one and build it up."
          />
        )}
      </Card>

      <div className="space-y-4">
        <Card>
          <CardHeader title="New system" />
          <div className="space-y-3 p-4">
            <Field label="Name">
              <Input
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="Team under pressure"
              />
            </Field>
            {error ? <ErrorNote message={error} /> : null}
            <Button variant="primary" className="w-full" onClick={create} disabled={createSystem.isPending}>
              Create empty system
            </Button>
          </div>
        </Card>

        <Card>
          <CardHeader
            title="Start from a preset"
            subtitle="Complete, runnable models you can then take apart"
          />
          {/* Named for the end-to-end tests: a system created from a preset shares its name, so
              the presets need to be addressable independently of the systems list. */}
          <ul className="divide-y divide-[var(--border)]" data-testid="preset-list">
            {(presets.data ?? []).map((preset) => (
              <li key={preset.id} className="px-4 py-3">
                <div className="text-sm font-medium">{preset.label}</div>
                <p className="mt-0.5 text-xs text-[var(--text-muted)]">{preset.description}</p>
                <Button
                  size="sm"
                  className="mt-2"
                  disabled={fromPreset.isPending}
                  onClick={async () => {
                    // Without this catch a failed request rejected into nothing: the button did
                    // not navigate, showed no error, and looked simply dead. That is how a 403
                    // from the API hid for an entire session.
                    setError(null);
                    try {
                      const created = await fromPreset.mutateAsync({ presetId: preset.id });
                      void navigate({ to: "/systems/$systemId", params: { systemId: created.id } });
                    } catch (cause) {
                      setError(
                        cause instanceof Error ? cause.message : "Could not create a system from that preset.",
                      );
                    }
                  }}
                >
                  Use this
                </Button>
              </li>
            ))}
            {error ? (
              <li className="px-4 py-3">
                <ErrorNote message={error} />
              </li>
            ) : null}
            {presets.isLoading ? (
              <li className="px-4 py-3 text-xs text-[var(--text-muted)]">Loading presets…</li>
            ) : null}
          </ul>
        </Card>
      </div>
    </div>
  );
}
