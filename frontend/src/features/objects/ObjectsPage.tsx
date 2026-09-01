import type { ObjectTypeSpec } from "@/api/types";
import {
  useCreateObjectTemplate,
  useDeleteObjectTemplate,
  useObjectTemplate,
  useObjectTemplateVersions,
  usePublishObjectTemplate,
  useObjectTemplates,
  useSaveObjectTemplate,
} from "@/api/queries";
import { ConfirmButton } from "@/components/ui/ConfirmButton";
import { PanelBody, panelPhase } from "@/components/ui/PanelBody";
import {
  Badge,
  Button,
  Card,
  CardHeader,
  ErrorNote,
  Field,
  Input,
  Spinner,
} from "@/components/ui/primitives";
import { ObjectTypeEditor, emptyObjectType } from "@/features/objects/ObjectTypeEditor";
import { formatRelativeTime } from "@/lib/utils";
import { useEffect, useRef, useState } from "react";

/**
 * Object types that outlive any one system.
 *
 * A type defined inside a system cannot be used by the next one, so the same "person" gets rebuilt
 * for every model. A template is that definition published once, versioned so a system that was
 * built against one shape of it does not silently acquire another.
 */
export function ObjectsPage() {
  const templates = useObjectTemplates();
  const createTemplate = useCreateObjectTemplate();
  const deleteTemplate = useDeleteObjectTemplate();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  return (
    <div className="mx-auto grid max-w-6xl gap-4 p-4 lg:grid-cols-[300px_1fr]">
      <Card className="h-fit">
        <CardHeader
          title="Object types"
          subtitle="Shared across systems"
          actions={templates.isFetching ? <Spinner /> : null}
        />
        <PanelBody
          phase={panelPhase(templates, (templates.data ?? []).length === 0)}
          error={templates.error}
          emptyTitle="No shared types yet"
          emptyDescription="Create one here and any system can use it, or define a type inside a single system instead."
        >
          <ul className="divide-y divide-[var(--border)]">
            {(templates.data ?? []).map((template) => (
              <li
                key={template.id}
                className={
                  "flex items-center gap-2 px-3 py-2 " +
                  (template.id === selectedId ? "bg-[var(--surface-2)]" : "")
                }
              >
                <button
                  type="button"
                  className="min-w-0 flex-1 text-left"
                  onClick={() => setSelectedId(template.id)}
                >
                  <span className="block truncate text-sm font-medium">{template.name}</span>
                  <span className="block truncate text-xs text-[var(--text-muted)]">
                    {template.draft ? `${template.draft.variables.length} variables` : "no draft"} · edited{" "}
                    {formatRelativeTime(template.updatedAt)}
                  </span>
                </button>
                <ConfirmButton
                  onConfirm={() => {
                    if (template.id === selectedId) setSelectedId(null);
                    deleteTemplate.mutate(template.id);
                  }}
                  title="Delete this type and all its published versions"
                />
              </li>
            ))}
          </ul>
        </PanelBody>

        <div className="border-t border-[var(--border)] p-3">
          {error ? <ErrorNote message={error} /> : null}
          <Button
            className="w-full"
            disabled={createTemplate.isPending}
            onClick={async () => {
              setError(null);
              try {
                const created = await createTemplate.mutateAsync({
                  name: "Untitled type",
                  description: "",
                  draft: emptyObjectType(),
                });
                setSelectedId(created.id);
              } catch (cause) {
                setError(cause instanceof Error ? cause.message : "Could not create the type.");
              }
            }}
          >
            + New object type
          </Button>
        </div>
      </Card>

      {selectedId ? (
        <TemplateEditor key={selectedId} templateId={selectedId} />
      ) : (
        <Card className="min-w-0">
          <CardHeader title="Nothing selected" subtitle="Pick a type on the left, or create one" />
          <div className="p-6 text-sm text-[var(--text-muted)]">
            An object type says what its instances are made of: the variables they carry, the ranges those
            variables live in, and how they remember what happens to them.
          </div>
        </Card>
      )}
    </div>
  );
}

function TemplateEditor({ templateId }: { templateId: string }) {
  const template = useObjectTemplate(templateId);
  const versions = useObjectTemplateVersions(templateId);
  const save = useSaveObjectTemplate(templateId);
  const publish = usePublishObjectTemplate(templateId);

  const [name, setName] = useState("");
  const [draft, setDraft] = useState<ObjectTypeSpec | null>(null);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (template.data && draft === null) {
      setName(template.data.name);
      setDraft(template.data.draft ?? emptyObjectType());
    }
  }, [template.data, draft]);

  // Autosaved on a debounce, like the system editor: the two behave the same way because they are
  // the same kind of thing, and a type editor that needed saving would be the odd one out.
  const timer = useRef<number | null>(null);
  useEffect(() => {
    if (!draft || !dirty) return undefined;
    if (timer.current) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => {
      save.mutate(
        { name, description: template.data?.description ?? "", draft },
        { onSuccess: () => setDirty(false), onError: (cause) => setError(cause.message) },
      );
    }, 600);
    return () => {
      if (timer.current) window.clearTimeout(timer.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft, name, dirty]);

  const latest = versions.data?.[0];
  const published = versions.data ?? [];

  return (
    <Card className="min-w-0">
      <CardHeader
        title={
          <input
            className="w-full bg-transparent text-sm font-semibold outline-none"
            value={name}
            aria-label="Type name"
            onChange={(event) => {
              setName(event.target.value);
              setDirty(true);
            }}
          />
        }
        subtitle={latest ? `v${latest.version} published` : "never published"}
        actions={
          <>
            <span className="text-xs text-[var(--text-muted)]">{dirty ? "saving…" : "saved"}</span>
            {published.length > 0 ? <Badge tone="accent">{published.length} versions</Badge> : null}
            <Button
              variant="primary"
              size="sm"
              disabled={publish.isPending || !draft}
              title="Freeze the current definition so systems can pin to it"
              onClick={() =>
                publish.mutate(undefined, {
                  onError: (cause) => setError(cause.message),
                })
              }
            >
              Publish
            </Button>
          </>
        }
      />
      <div className="p-4">
        {error ? <ErrorNote message={error} /> : null}
        <PanelBody
          phase={panelPhase(template, draft === null)}
          error={template.error}
          emptyTitle="Loading the type"
          skeletonRows={6}
        >
          {draft ? (
            <ObjectTypeEditor
              type={draft}
              onChange={(next) => {
                setDraft(next);
                setDirty(true);
              }}
            />
          ) : null}
        </PanelBody>

        {published.length > 0 ? (
          <div className="mt-4 border-t border-[var(--border)] pt-3">
            <h3 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-[var(--text-muted)]">
              Published versions
            </h3>
            <ul className="space-y-1 text-xs text-[var(--text-muted)]">
              {published.map((version) => (
                <li key={version.id} className="flex items-center gap-2">
                  <Badge tone="neutral">v{version.version}</Badge>
                  <span>{version.typeSpec.variables.length} variables</span>
                  <span className="ml-auto">{formatRelativeTime(version.publishedAt)}</span>
                </li>
              ))}
            </ul>
            <p className="mt-2 text-xs text-[var(--text-muted)]">
              A system embeds a copy of the version it was built against, so publishing here never changes a
              system that already exists. The system editor shows when a copy has fallen behind.
            </p>
          </div>
        ) : null}
      </div>
    </Card>
  );
}

/** A field kept for callers that want to name a template inline. */
export function TemplateNameField({
  value,
  onChange,
}: {
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <Field label="Name">
      <Input value={value} onChange={(event) => onChange(event.target.value)} />
    </Field>
  );
}
