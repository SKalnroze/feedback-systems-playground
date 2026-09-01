import type { ObjectTypeSpec } from "@/api/types";
import { useCreateObjectTemplate } from "@/api/queries";
import { api } from "@/api/client";
import { Button, Card, CardHeader, ErrorNote, Field, Input } from "@/components/ui/primitives";
import { ObjectTypeEditor, emptyObjectType } from "@/features/objects/ObjectTypeEditor";
import { useState } from "react";

/**
 * Defining an object type without leaving the system you are building.
 *
 * The choice of where it is saved is the whole point of the dialog. A type that only this system
 * needs belongs in this system, where it can be changed freely; one that other systems will want
 * belongs in the shared library, where it is versioned so changing it later cannot quietly alter a
 * system that has already been built and run against it.
 */
export function ObjectTypeDialog({
  onClose,
  onSaveLocal,
  onSaveGlobal,
}: {
  onClose: () => void;
  onSaveLocal: (type: ObjectTypeSpec) => void;
  /** Called with the type already stamped with the template it came from. */
  onSaveGlobal: (type: ObjectTypeSpec) => void;
}) {
  const [type, setType] = useState<ObjectTypeSpec>(() => emptyObjectType("thing"));
  const [shared, setShared] = useState(false);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);

  const createTemplate = useCreateObjectTemplate();

  async function save() {
    setError(null);
    if (!type.id.trim()) {
      setError("The type needs an id, since links and rules refer to it by name.");
      return;
    }
    if (!shared) {
      onSaveLocal(type);
      onClose();
      return;
    }
    try {
      // Published immediately: an unpublished template has no version for the system to pin to,
      // and a type that silently followed the latest draft would defeat the point of versioning.
      const template = await createTemplate.mutateAsync({
        name: name || type.label || type.id,
        description: "",
        draft: type,
      });
      // Published straight away: the id only exists once the template does, and a system cannot
      // pin to a template that has never been published.
      const version = await api.publishObjectTemplate(template.id);
      onSaveGlobal({ ...type, templateId: template.id, templateVersion: version.version });
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not save the shared type.");
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-auto bg-black/50 p-6">
      <Card className="w-full max-w-2xl">
        <CardHeader
          title="New object type"
          subtitle="What its instances are made of"
          actions={
            <Button variant="ghost" size="sm" onClick={onClose}>
              Cancel
            </Button>
          }
        />
        <div className="max-h-[70vh] overflow-auto p-4">
          <ObjectTypeEditor type={type} onChange={setType} />
        </div>

        <div className="space-y-3 border-t border-[var(--border)] p-4">
          {error ? <ErrorNote message={error} /> : null}

          <div className="space-y-2">
            <label className="flex items-start gap-2 text-sm">
              <input
                type="radio"
                className="mt-1"
                checked={!shared}
                onChange={() => setShared(false)}
              />
              <span>
                <span className="font-medium">Just this system</span>
                <span className="block text-xs text-[var(--text-muted)]">
                  Lives in this system's draft. Change it whenever you like; nothing else is affected.
                </span>
              </span>
            </label>
            <label className="flex items-start gap-2 text-sm">
              <input type="radio" className="mt-1" checked={shared} onChange={() => setShared(true)} />
              <span>
                <span className="font-medium">Shared object type</span>
                <span className="block text-xs text-[var(--text-muted)]">
                  Published to the library so other systems can use it. This system pins the version it was
                  built against, and tells you when the library moves ahead of it.
                </span>
              </span>
            </label>
          </div>

          {shared ? (
            <Field label="Library name" hint="How it appears under Objects.">
              <Input
                value={name}
                placeholder={type.label || type.id}
                onChange={(event) => setName(event.target.value)}
              />
            </Field>
          ) : null}

          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button variant="primary" onClick={save} disabled={createTemplate.isPending}>
              Add to system
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}
