import { EmptyState, ErrorNote, Skeleton } from "@/components/ui/primitives";
import type { ReactNode } from "react";

/**
 * The four states any panel that loads something can be in.
 *
 * Panels used to have two: content, or an empty state. That collapses "nothing to show" and "not
 * asked yet" into the same message, so a panel would announce that a run had no memories, no
 * relationships and no chartable series during the second or two it took to find out. The reader
 * takes the panel at its word, decides the run is empty, and goes elsewhere - which is worse than
 * showing nothing at all, because it is a confident wrong answer rather than an absent one.
 */
export type PanelPhase = "loading" | "error" | "empty" | "ready";

/** A query as far as this needs to care: enough of TanStack Query's shape to classify it. */
type QueryLike = {
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  data: unknown;
  fetchStatus?: "fetching" | "paused" | "idle";
};

/**
 * Works out which of the four states a panel is in.
 *
 * A disabled query - one waiting on an id or a selection - reports `isLoading` forever without
 * ever having asked a question, so that case is checked first and treated as "nothing selected"
 * rather than as a load in progress. Getting this backwards produces a skeleton that never
 * resolves, which is its own kind of lie.
 */
export function panelPhase(query: QueryLike, isEmpty: boolean, enabled = true): PanelPhase {
  if (!enabled) return "empty";
  if (query.isError) return "error";
  if (query.data === undefined && query.fetchStatus !== "idle") return "loading";
  if (query.isLoading) return "loading";
  return isEmpty ? "empty" : "ready";
}

/**
 * Renders the right thing for a panel's phase.
 *
 * The empty state is only ever reached once the answer is actually known, which is the whole point.
 */
export function PanelBody({
  phase,
  error,
  emptyTitle,
  emptyDescription,
  skeletonRows = 4,
  height,
  children,
}: {
  phase: PanelPhase;
  error?: unknown;
  emptyTitle: string;
  emptyDescription?: string;
  skeletonRows?: number;
  /** Fixed height for chart-shaped panels, so the layout does not jump when data lands. */
  height?: number;
  children: ReactNode;
}) {
  if (phase === "loading") {
    return height ? (
      <div style={{ height }} className="p-3">
        <div className="h-full animate-pulse rounded-md bg-[var(--surface-2)]" />
      </div>
    ) : (
      <Skeleton rows={skeletonRows} />
    );
  }

  if (phase === "error") {
    return (
      <div className="p-3">
        <ErrorNote message={error instanceof Error ? error.message : "Could not load this panel."} />
      </div>
    );
  }

  if (phase === "empty") {
    return (
      <div style={height ? { height } : undefined} className={height ? "flex items-center" : undefined}>
        <div className="w-full">
          <EmptyState title={emptyTitle} description={emptyDescription} />
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
