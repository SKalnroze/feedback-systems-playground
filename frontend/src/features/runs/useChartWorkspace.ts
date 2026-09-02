import type { ChartPanelConfig } from "@/features/runs/ChartWorkspace";
import type { SeriesTransform } from "@/features/runs/seriesTransforms";
import { useCallback, useEffect, useState } from "react";

/** Everything about how a run is currently being looked at. */
export type ChartWorkspaceState = {
  panels: ChartPanelConfig[];
  resolution: number;
  transform: SeriesTransform;
  smoothing: number;
  lockAxis: boolean;
  columns: 1 | 2;
  panelHeight: number;
};

export const DEFAULT_WORKSPACE: ChartWorkspaceState = {
  panels: [],
  resolution: 0,
  transform: "raw",
  smoothing: 1,
  lockAxis: false,
  columns: 1,
  panelHeight: 260,
};

const VERSION = 1;
const key = (runId: string) => `fsp.workspace.v${VERSION}.${runId}`;

/**
 * Remembers how a run was being looked at.
 *
 * Choosing which of eighty-odd series to chart, at what resolution and in what arrangement, is real
 * work, and until now every reload threw it away — the theme was the only thing in the whole
 * application that persisted. Keyed by run because the interesting comparison is a run against
 * itself over time, not one layout imposed on every run.
 *
 * Stored in `localStorage` rather than on the server: it is a view preference belonging to this
 * browser, and putting it in the database would make it something other people inherit.
 */
export function useChartWorkspace(runId: string) {
  const [state, setState] = useState<ChartWorkspaceState>(DEFAULT_WORKSPACE);
  const [restored, setRestored] = useState(false);

  useEffect(() => {
    setRestored(false);
    try {
      const stored = window.localStorage.getItem(key(runId));
      // Merged over the defaults so a workspace saved by an older build, missing fields added
      // since, still opens instead of rendering a chart with undefined settings.
      setState(stored ? { ...DEFAULT_WORKSPACE, ...(JSON.parse(stored) as ChartWorkspaceState) } : DEFAULT_WORKSPACE);
    } catch {
      // Corrupt or unreadable storage is not worth failing a page over.
      setState(DEFAULT_WORKSPACE);
    }
    setRestored(true);
  }, [runId]);

  useEffect(() => {
    // Never write before the first read has happened, or an empty default would overwrite a saved
    // workspace in the instant between mount and restore.
    if (!restored) return;
    try {
      window.localStorage.setItem(key(runId), JSON.stringify(state));
    } catch {
      // Storage can be full or disabled; losing the layout is better than losing the page.
    }
  }, [runId, state, restored]);

  const update = useCallback((change: Partial<ChartWorkspaceState>) => {
    setState((current) => ({ ...current, ...change }));
  }, []);

  const reset = useCallback(() => setState(DEFAULT_WORKSPACE), []);

  return { state, update, reset, restored };
}
