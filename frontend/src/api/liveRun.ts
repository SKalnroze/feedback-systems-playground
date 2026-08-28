import { api } from "@/api/client";
import type { RunSnapshot } from "@/api/types";
import { useEffect } from "react";
import { create } from "zustand";

type LiveState = {
  snapshots: Record<string, RunSnapshot>;
  connected: Record<string, boolean>;
  apply: (snapshot: RunSnapshot) => void;
  setConnected: (runId: string, connected: boolean) => void;
};

/**
 * Live run state, kept outside the query cache.
 *
 * Ticks arrive several times a second while a run is going. Pushing each one through TanStack
 * Query would invalidate and re-render far more than the two or three components that actually
 * display a live number, so the stream lands in its own store and only its subscribers re-render.
 * Everything historical still comes from the query cache.
 */
export const useLiveRuns = create<LiveState>((set) => ({
  snapshots: {},
  connected: {},
  apply: (snapshot) =>
    set((state) => ({ snapshots: { ...state.snapshots, [snapshot.runId]: snapshot } })),
  setConnected: (runId, connected) =>
    set((state) => ({ connected: { ...state.connected, [runId]: connected } })),
}));

/**
 * Subscribes to a run's event stream for as long as the component is mounted.
 *
 * `EventSource` reconnects by itself, so there is no retry logic here; the connected flag simply
 * reflects whether the browser currently has the stream open.
 */
export function useRunStream(runId: string | undefined): void {
  useEffect(() => {
    if (!runId) return undefined;

    const source = new EventSource(api.streamUrl(runId));
    const { apply, setConnected } = useLiveRuns.getState();

    const handle = (event: MessageEvent<string>) => {
      try {
        apply(JSON.parse(event.data) as RunSnapshot);
      } catch {
        // A malformed frame is not worth tearing the stream down for.
      }
    };

    source.addEventListener("tick", handle as EventListener);
    source.addEventListener("status", handle as EventListener);
    source.onopen = () => setConnected(runId, true);
    source.onerror = () => setConnected(runId, false);

    return () => {
      source.close();
      useLiveRuns.getState().setConnected(runId, false);
    };
  }, [runId]);
}

/** The latest live snapshot for a run, or undefined before the first frame arrives. */
export function useLiveSnapshot(runId: string | undefined): RunSnapshot | undefined {
  return useLiveRuns((state) => (runId ? state.snapshots[runId] : undefined));
}

export function useStreamConnected(runId: string | undefined): boolean {
  return useLiveRuns((state) => (runId ? (state.connected[runId] ?? false) : false));
}
