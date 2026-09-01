import type { ObjectTypeSpec } from "@/api/types";
import { api } from "@/api/client";
import { Badge, Button, ErrorNote } from "@/components/ui/primitives";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

/**
 * Whether a type copied from the shared library has fallen behind it.
 *
 * A system embeds a copy of the version it was built against, which is what stops a shared edit
 * from silently changing a model that has already been run. The cost of that safety is that a
 * system can drift out of date without anyone noticing, so it has to be said out loud — along with
 * what actually changed, since "out of date" on its own gives an author nothing to decide with.
 */
export function TemplateStatus({
  type,
  onUpdate,
}: {
  type: ObjectTypeSpec;
  /** Replaces the embedded type, keeping its id so links and objects still resolve. */
  onUpdate: (next: ObjectTypeSpec) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const templateId = type.templateId ?? undefined;
  const usedVersion = type.templateVersion ?? undefined;

  const latest = useQuery({
    queryKey: ["objectTemplateLatest", templateId ?? ""],
    queryFn: () => api.latestObjectTemplateVersion(templateId!),
    enabled: Boolean(templateId),
    // A missing template is a legitimate state — it may have been deleted — so do not retry it
    // into a spinner that never resolves.
    retry: false,
  });

  const diff = useQuery({
    queryKey: ["objectTemplateDiff", templateId ?? "", usedVersion ?? 0, latest.data?.version ?? 0],
    queryFn: () => api.diffObjectTemplate(templateId!, usedVersion!, latest.data!.version),
    enabled: expanded && Boolean(templateId) && Boolean(usedVersion) && Boolean(latest.data),
    retry: false,
  });

  if (!templateId || !usedVersion) {
    return null;
  }
  if (latest.isError) {
    return (
      <p className="text-xs text-[var(--text-muted)]">
        From a shared type that is no longer in the library. The copy in this system still works.
      </p>
    );
  }
  if (!latest.data) {
    return null;
  }

  const behind = latest.data.version > usedVersion;
  if (!behind) {
    return (
      <p className="text-xs text-[var(--text-muted)]">
        Shared type, up to date with v{usedVersion}.
      </p>
    );
  }

  return (
    <div className="space-y-2 rounded border border-[var(--status-warning)]/40 bg-[var(--status-warning)]/5 p-2">
      <div className="flex items-center gap-2">
        <Badge tone="warning">v{usedVersion}</Badge>
        <span className="text-xs text-[var(--text-secondary)]">
          The library is on v{latest.data.version}.
        </span>
        <Button size="sm" variant="ghost" className="ml-auto" onClick={() => setExpanded((open) => !open)}>
          {expanded ? "Hide changes" : "What changed?"}
        </Button>
      </div>

      {expanded ? (
        <div className="space-y-1">
          {diff.isLoading ? (
            <p className="text-xs text-[var(--text-muted)]">Comparing…</p>
          ) : diff.data && diff.data.changes.length > 0 ? (
            <ul className="space-y-0.5 text-xs text-[var(--text-secondary)]">
              {diff.data.changes.map((change) => (
                <li key={`${change.kind}-${change.id}-${change.change}`}>
                  <span className="text-[var(--text-muted)]">{change.kind}</span> {change.id}{" "}
                  <Badge tone={change.change === "removed" ? "critical" : "neutral"}>{change.change}</Badge>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-xs text-[var(--text-muted)]">Nothing of substance changed.</p>
          )}

          {diff.data?.changes.some((change) => change.change === "removed") ? (
            <p className="text-xs text-[var(--status-warning)]">
              Updating drops variables this system may already be using; any starting values and links that
              refer to them are removed too.
            </p>
          ) : null}
        </div>
      ) : null}

      {error ? <ErrorNote message={error} /> : null}

      <Button
        size="sm"
        onClick={() => {
          setError(null);
          try {
            // The id is kept from the system's copy rather than taken from the template: objects
            // and links refer to the type by id, and adopting a renamed one would orphan them all.
            onUpdate({
              ...latest.data.typeSpec,
              id: type.id,
              templateId,
              templateVersion: latest.data.version,
            });
          } catch (cause) {
            setError(cause instanceof Error ? cause.message : "Could not update the type.");
          }
        }}
      >
        Update to v{latest.data.version}
      </Button>
    </div>
  );
}
