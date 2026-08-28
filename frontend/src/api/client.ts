import type {
  Checkpoint,
  LogEntry,
  MemoryView,
  ModuleView,
  Page,
  PaletteView,
  PresetView,
  RelationshipCell,
  RunDetail,
  RunSummary,
  SeriesDefinition,
  SeriesResponse,
  SystemDefinition,
  SystemSpec,
  SystemVersion,
  ValidationReport,
} from "./types";

/**
 * Thin wrapper over `fetch`.
 *
 * Same-origin `/api` everywhere: the dev server proxies it and nginx proxies it in the container,
 * so the client never carries an environment-specific base URL.
 */
const BASE = "/api/v1";

/** An error response from the API, carrying the problem detail the backend sent. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
    readonly problem: Record<string, unknown> | null,
  ) {
    super(detail);
    this.name = "ApiError";
  }

  /** Validation issues, when the failure was a rejected spec. */
  get issues(): { elementId: string; message: string }[] {
    const raw = this.problem?.["issues"];
    return Array.isArray(raw) ? (raw as { elementId: string; message: string }[]) : [];
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
      Accept: "application/json",
      ...init?.headers,
    },
  });

  if (!response.ok) {
    const body = await response.text();
    let problem: Record<string, unknown> | null = null;
    try {
      problem = body ? (JSON.parse(body) as Record<string, unknown>) : null;
    } catch {
      // A non-JSON error body is still worth surfacing verbatim.
    }
    const detail = (problem?.["detail"] as string | undefined) ?? body ?? response.statusText;
    throw new ApiError(response.status, detail, problem);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) });

export const api = {
  // --- modules ---
  modules: () => request<ModuleView[]>("/modules"),
  presets: () => request<PresetView[]>("/presets"),
  palette: () => request<PaletteView>("/palette"),

  // --- systems ---
  systems: () => request<SystemDefinition[]>("/systems"),
  system: (id: string) => request<SystemDefinition>(`/systems/${id}`),
  createSystem: (name: string, description: string, draft: SystemSpec | null) =>
    request<SystemDefinition>("/systems", { method: "POST", ...json({ name, description, draft }) }),
  createFromPreset: (presetId: string, name?: string) =>
    request<SystemDefinition>("/systems/from-preset", {
      method: "POST",
      ...json({ presetId, name: name ?? null }),
    }),
  saveDraft: (id: string, name: string, description: string, draft: SystemSpec) =>
    request<SystemDefinition>(`/systems/${id}/draft`, {
      method: "PUT",
      ...json({ name, description, draft }),
    }),
  deleteSystem: (id: string) => request<void>(`/systems/${id}`, { method: "DELETE" }),
  publish: (id: string) => request<SystemVersion>(`/systems/${id}/versions`, { method: "POST" }),
  versions: (id: string) => request<SystemVersion[]>(`/systems/${id}/versions`),
  latestVersion: (id: string) => request<SystemVersion>(`/systems/${id}/versions/latest`),
  validateDraft: (id: string) => request<ValidationReport>(`/systems/${id}/validation`),
  validateSpec: (spec: SystemSpec) =>
    request<ValidationReport>("/systems/validate", { method: "POST", ...json({ spec }) }),

  // --- runs ---
  runs: () => request<RunSummary[]>("/runs"),
  run: (id: string) => request<RunDetail>(`/runs/${id}`),
  forks: (id: string) => request<RunSummary[]>(`/runs/${id}/forks`),
  createRun: (input: {
    systemVersionId: string;
    name?: string;
    /** Sent as a string so a 64-bit seed reaches the server exactly as the user typed it. */
    seed?: string | null;
    speed?: number | null;
    autoStart?: boolean;
  }) =>
    request<RunSummary>("/runs", {
      method: "POST",
      ...json({
        systemVersionId: input.systemVersionId,
        name: input.name ?? null,
        seed: input.seed ?? null,
        speed: input.speed ?? null,
        autoStart: input.autoStart ?? false,
      }),
    }),
  control: (id: string, action: "START" | "PAUSE" | "RESUME" | "STOP" | "STEP", ticks?: number) =>
    request<RunSummary>(`/runs/${id}/control`, {
      method: "POST",
      ...json({ action, ticks: ticks ?? null }),
    }),
  setSpeed: (id: string, ticksPerSecond: number) =>
    request<RunSummary>(`/runs/${id}/speed`, { method: "PATCH", ...json({ ticksPerSecond }) }),
  renameRun: (id: string, name: string) =>
    request<RunSummary>(`/runs/${id}/name`, { method: "PATCH", ...json({ name }) }),
  deleteRun: (id: string) => request<void>(`/runs/${id}`, { method: "DELETE" }),

  // --- checkpoints ---
  checkpoints: (id: string) => request<Checkpoint[]>(`/runs/${id}/checkpoints`),
  checkpoint: (id: string, label: string | null) =>
    request<Checkpoint[]>(`/runs/${id}/checkpoints`, { method: "POST", ...json({ label }) }),
  restore: (id: string, checkpointId: string, fork: boolean, name?: string) =>
    request<RunSummary>(`/runs/${id}/restore`, {
      method: "POST",
      ...json({ checkpointId, fork, name: name ?? null }),
    }),

  // --- observability ---
  seriesCatalogue: (id: string) => request<SeriesDefinition[]>(`/runs/${id}/series`),
  seriesData: (id: string, keys: string[], from = 0, to = -1, resolution = 0) => {
    const query = new URLSearchParams({
      keys: keys.join(","),
      from: String(from),
      to: String(to),
      resolution: String(resolution),
    });
    return request<SeriesResponse>(`/runs/${id}/series/data?${query}`);
  },
  log: (id: string, options: { type?: string; from?: number; to?: number; limit?: number } = {}) => {
    const query = new URLSearchParams({ limit: String(options.limit ?? 200) });
    if (options.type) query.set("type", options.type);
    if (options.from !== undefined) query.set("from", String(options.from));
    if (options.to !== undefined) query.set("to", String(options.to));
    return request<Page<LogEntry>>(`/runs/${id}/log?${query}`);
  },
  memories: (id: string, ownerId?: string, limit = 200) => {
    const query = new URLSearchParams({ limit: String(limit) });
    if (ownerId) query.set("ownerId", ownerId);
    return request<Page<MemoryView>>(`/runs/${id}/memories?${query}`);
  },
  relationships: (id: string) => request<RelationshipCell[]>(`/runs/${id}/relationships`),

  /** URL of the live event stream; consumed with `EventSource`. */
  streamUrl: (id: string) => `${BASE}/runs/${id}/stream`,
};
