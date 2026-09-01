import { cn } from "@/lib/utils";
import { useEffect, useRef, useState } from "react";

/**
 * A description you can edit where it is shown.
 *
 * Runs, systems and checkpoints all accumulate, and after a dozen forks the list is a column of
 * near-identical names. The thing that tells them apart is why each exists, and that only gets
 * written down if writing it is easier than opening a form. So the text is the control: click it
 * and type.
 *
 * Saved on blur rather than on a button, and on Escape the edit is abandoned — a note about a
 * simulation is not worth a confirmation dialog either way.
 */
export function EditableDescription({
  value,
  onSave,
  placeholder = "Add a note about what this is for…",
  className,
  rows = 2,
}: {
  value: string;
  onSave: (description: string) => void;
  placeholder?: string;
  className?: string;
  rows?: number;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value);
  const area = useRef<HTMLTextAreaElement | null>(null);

  // Follows the value when it changes elsewhere, but never while being typed into: pulling the
  // text out from under someone mid-sentence is worse than showing something slightly stale.
  useEffect(() => {
    if (!editing) setDraft(value);
  }, [value, editing]);

  useEffect(() => {
    if (editing) area.current?.focus();
  }, [editing]);

  if (!editing) {
    return (
      <button
        type="button"
        onClick={() => setEditing(true)}
        title="Click to edit"
        className={cn(
          "w-full rounded px-1 py-0.5 text-left text-xs hover:bg-[var(--surface-2)]",
          value ? "text-[var(--text-secondary)]" : "italic text-[var(--text-muted)]",
          className,
        )}
      >
        {value || placeholder}
      </button>
    );
  }

  return (
    <textarea
      ref={area}
      rows={rows}
      value={draft}
      aria-label="Description"
      className={cn(
        "w-full rounded border border-[var(--border)] bg-[var(--surface-0)] px-2 py-1 text-xs",
        "text-[var(--text-primary)] outline-none focus:border-[var(--accent)]",
        className,
      )}
      onChange={(event) => setDraft(event.target.value)}
      onBlur={() => {
        setEditing(false);
        if (draft !== value) onSave(draft);
      }}
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          setDraft(value);
          setEditing(false);
        }
        // Enter saves; shift-enter keeps the newline, since a couple of sentences is the point.
        if (event.key === "Enter" && !event.shiftKey) {
          event.preventDefault();
          event.currentTarget.blur();
        }
      }}
    />
  );
}
