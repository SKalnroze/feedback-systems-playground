import { Card, CardHeader } from "@/components/ui/primitives";
import { useEffect, useState } from "react";

type Shortcut = { keys: string; does: string; where: string };

const SHORTCUTS: Shortcut[] = [
  { keys: "Space", does: "Run or pause", where: "a run" },
  { keys: "→", does: "Step forward", where: "a run" },
  { keys: "Shift + →", does: "Step ten times as far", where: "a run" },
  { keys: "Ctrl + Z", does: "Undo", where: "the system editor" },
  { keys: "Ctrl + Shift + Z", does: "Redo", where: "the system editor" },
  { keys: "?", does: "Show this list", where: "anywhere" },
  { keys: "Esc", does: "Close this, or abandon an edit", where: "anywhere" },
];

/**
 * The keyboard shortcuts, findable.
 *
 * Space, the arrow keys and undo have all worked for some time and none of them announced
 * themselves, which makes them features only their author knows about. `?` is the conventional way
 * to ask an application what it can do, so it is the one shortcut worth guessing.
 */
export function ShortcutsOverlay() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null;
      const typing =
        target?.tagName === "INPUT" || target?.tagName === "TEXTAREA" || target?.isContentEditable;
      if (typing) return;

      if (event.key === "?") {
        event.preventDefault();
        setOpen((was) => !was);
      }
      if (event.key === "Escape") setOpen(false);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        title="Keyboard shortcuts (?)"
        aria-label="Keyboard shortcuts"
        className="rounded-md px-2 py-1 text-xs text-[var(--text-muted)] hover:bg-[var(--surface-2)]"
      >
        ?
      </button>
    );
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-6"
      onClick={() => setOpen(false)}
      role="presentation"
    >
      <Card className="w-full max-w-md" onClick={(event) => event.stopPropagation()}>
        <CardHeader title="Keyboard shortcuts" subtitle="Press ? again, or Escape, to close" />
        <ul className="divide-y divide-[var(--border)]">
          {SHORTCUTS.map((shortcut) => (
            <li key={shortcut.keys} className="flex items-center gap-3 px-4 py-2">
              <kbd className="tabular rounded border border-[var(--border)] bg-[var(--surface-2)] px-1.5 py-0.5 text-xs">
                {shortcut.keys}
              </kbd>
              <span className="flex-1 text-sm text-[var(--text-secondary)]">{shortcut.does}</span>
              <span className="text-xs text-[var(--text-muted)]">{shortcut.where}</span>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
