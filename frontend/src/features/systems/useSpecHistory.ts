import type { SystemSpec } from "@/api/types";
import { useCallback, useRef, useState } from "react";

/** How many steps back the editor can go. Deep enough to undo a bad idea, not a whole session. */
const HISTORY_LIMIT = 50;

/**
 * Undo and redo for the spec being edited.
 *
 * The editor autosaves, which is the right default but removes the usual safety net: a deleted
 * link or a mistyped gain is persisted within a second, and there is no unsaved state to abandon.
 * History is what puts the net back.
 *
 * Snapshots are whole specs rather than diffs. A spec is a few tens of kilobytes at most, fifty of
 * them cost less than one chart panel, and comparing two whole objects is a great deal harder to
 * get subtly wrong than replaying inverse operations.
 */
export function useSpecHistory(initial: SystemSpec | null) {
  const [spec, setSpecState] = useState<SystemSpec | null>(initial);
  const past = useRef<SystemSpec[]>([]);
  const future = useRef<SystemSpec[]>([]);
  // Rendered counts, so the buttons enable and disable as the stacks change.
  const [depths, setDepths] = useState({ undo: 0, redo: 0 });

  const sync = useCallback(() => {
    setDepths({ undo: past.current.length, redo: future.current.length });
  }, []);

  /** Replaces the spec without recording history: used when loading, not when editing. */
  const reset = useCallback(
    (next: SystemSpec | null) => {
      past.current = [];
      future.current = [];
      setSpecState(next);
      sync();
    },
    [sync],
  );

  const update = useCallback(
    (mutate: (current: SystemSpec) => SystemSpec) => {
      setSpecState((current) => {
        if (!current) return current;
        const next = mutate(current);
        if (next === current) return current;
        past.current = [...past.current, current].slice(-HISTORY_LIMIT);
        // Any new edit abandons the redo branch, the same as every other editor.
        future.current = [];
        return next;
      });
      sync();
    },
    [sync],
  );

  const undo = useCallback(() => {
    setSpecState((current) => {
      const previous = past.current.at(-1);
      if (!current || previous === undefined) return current;
      past.current = past.current.slice(0, -1);
      future.current = [...future.current, current];
      return previous;
    });
    sync();
  }, [sync]);

  const redo = useCallback(() => {
    setSpecState((current) => {
      const next = future.current.at(-1);
      if (!current || next === undefined) return current;
      future.current = future.current.slice(0, -1);
      past.current = [...past.current, current];
      return next;
    });
    sync();
  }, [sync]);

  return {
    spec,
    update,
    reset,
    undo,
    redo,
    canUndo: depths.undo > 0,
    canRedo: depths.redo > 0,
  };
}
