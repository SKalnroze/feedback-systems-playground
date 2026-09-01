import { api } from "@/api/client";
import { emptySpec } from "@/features/systems/specGraph";
import type { ObjectTypeSpec, SystemSpec } from "@/api/types";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

/**
 * Query keys, in one place.
 *
 * Invalidations are the main source of "why did this not refresh" bugs, so the keys are built from
 * a single tree rather than written out at each call site.
 */
export const keys = {
  modules: ["modules"] as const,
  presets: ["presets"] as const,
  palette: ["palette"] as const,
  systems: ["systems"] as const,
  system: (id: string) => ["systems", id] as const,
  versions: (id: string) => ["systems", id, "versions"] as const,
  validation: (id: string) => ["systems", id, "validation"] as const,
  runs: ["runs"] as const,
  run: (id: string) => ["runs", id] as const,
  forks: (id: string) => ["runs", id, "forks"] as const,
  checkpoints: (id: string) => ["runs", id, "checkpoints"] as const,
  seriesCatalogue: (id: string) => ["runs", id, "series"] as const,
  seriesData: (id: string, keysList: string[], from: number, to: number, resolution: number) =>
    ["runs", id, "series", "data", keysList.join(","), from, to, resolution] as const,
  log: (id: string, type: string | undefined, limit: number) => ["runs", id, "log", type ?? "all", limit] as const,
  memories: (id: string, ownerId: string | undefined) => ["runs", id, "memories", ownerId ?? "all"] as const,
  relationships: (id: string) => ["runs", id, "relationships"] as const,
};

// --- modules ------------------------------------------------------------------------------------

export const useModules = () =>
  useQuery({ queryKey: keys.modules, queryFn: api.modules, staleTime: Infinity });

export const usePresets = () =>
  useQuery({ queryKey: keys.presets, queryFn: api.presets, staleTime: Infinity });

export const usePalette = () =>
  useQuery({ queryKey: keys.palette, queryFn: api.palette, staleTime: Infinity });

/**
 * Editing a system's description.
 *
 * Goes through the existing draft endpoint rather than a new one: name, description and draft are
 * saved together already, and a second way to write the same row would be one more thing that can
 * disagree with the first.
 */
export function useDescribeSystem() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { id: string; name: string; description: string; draft: SystemSpec | null }) =>
      api.saveDraft(input.id, input.name, input.description, input.draft ?? emptySpec(input.name)),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.systems }),
  });
}

export function useDescribeRun(id: string | undefined) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (description: string) => api.describeRun(id!, description),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.runs });
      void client.invalidateQueries({ queryKey: keys.run(id ?? "") });
    },
  });
}

export function useDescribeCheckpoint(runId: string | undefined) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { checkpointId: string; description: string }) =>
      api.describeCheckpoint(input.checkpointId, input.description),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.checkpoints(runId ?? "") }),
  });
}

// --- object templates --------------------------------------------------------------------------

export const useObjectTemplates = () =>
  useQuery({ queryKey: ["objectTemplates"], queryFn: () => api.objectTemplates() });

export const useObjectTemplate = (id: string | undefined) =>
  useQuery({
    queryKey: ["objectTemplate", id ?? ""],
    queryFn: () => api.objectTemplate(id!),
    enabled: Boolean(id),
  });

export const useObjectTemplateVersions = (id: string | undefined) =>
  useQuery({
    queryKey: ["objectTemplateVersions", id ?? ""],
    queryFn: () => api.objectTemplateVersions(id!),
    enabled: Boolean(id),
  });

export function useSaveObjectTemplate(id: string | undefined) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { name: string; description: string; draft: ObjectTypeSpec }) =>
      api.saveObjectTemplate(id!, input.name, input.description, input.draft),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["objectTemplates"] });
      void client.invalidateQueries({ queryKey: ["objectTemplate", id ?? ""] });
    },
  });
}

export function useCreateObjectTemplate() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { name: string; description: string; draft: ObjectTypeSpec }) =>
      api.createObjectTemplate(input.name, input.description, input.draft),
    onSuccess: () => client.invalidateQueries({ queryKey: ["objectTemplates"] }),
  });
}

export function usePublishObjectTemplate(id: string | undefined) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api.publishObjectTemplate(id!),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["objectTemplateVersions", id ?? ""] });
      void client.invalidateQueries({ queryKey: ["objectTemplates"] });
    },
  });
}

export function useDeleteObjectTemplate() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.deleteObjectTemplate(id),
    onSuccess: () => client.invalidateQueries({ queryKey: ["objectTemplates"] }),
  });
}

// --- systems ------------------------------------------------------------------------------------

export const useSystems = () => useQuery({ queryKey: keys.systems, queryFn: () => api.systems() });

export const useSystem = (id: string | undefined) =>
  useQuery({ queryKey: keys.system(id ?? ""), queryFn: () => api.system(id!), enabled: Boolean(id) });

export const useVersions = (id: string | undefined) =>
  useQuery({ queryKey: keys.versions(id ?? ""), queryFn: () => api.versions(id!), enabled: Boolean(id) });

export const useDraftValidation = (id: string | undefined, enabled = true) =>
  useQuery({
    queryKey: keys.validation(id ?? ""),
    queryFn: () => api.validateDraft(id!),
    enabled: Boolean(id) && enabled,
  });

export function useCreateSystem() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { name: string; description?: string; draft?: SystemSpec | null }) =>
      api.createSystem(input.name, input.description ?? "", input.draft ?? null),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.systems }),
  });
}

export function useCreateFromPreset() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { presetId: string; name?: string }) =>
      api.createFromPreset(input.presetId, input.name),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.systems }),
  });
}

export function useSaveDraft(systemId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { name: string; description: string; draft: SystemSpec }) =>
      api.saveDraft(systemId, input.name, input.description, input.draft),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: keys.system(systemId) });
      client.invalidateQueries({ queryKey: keys.validation(systemId) });
    },
  });
}

export function usePublish(systemId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api.publish(systemId),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.versions(systemId) }),
  });
}

export function useDeleteSystem() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.deleteSystem(id),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.systems }),
  });
}

// --- runs ---------------------------------------------------------------------------------------

/**
 * The run list refreshes on a timer as well as on demand: runs advance in the background whether
 * or not anyone is looking at them, and a list that only updated on navigation would be wrong most
 * of the time.
 */
export const useRuns = () =>
  useQuery({ queryKey: keys.runs, queryFn: () => api.runs(), refetchInterval: 3000 });

export const useRun = (id: string | undefined) =>
  useQuery({ queryKey: keys.run(id ?? ""), queryFn: () => api.run(id!), enabled: Boolean(id) });

export const useForks = (id: string | undefined) =>
  useQuery({ queryKey: keys.forks(id ?? ""), queryFn: () => api.forks(id!), enabled: Boolean(id) });

export const useCheckpoints = (id: string | undefined) =>
  useQuery({
    queryKey: keys.checkpoints(id ?? ""),
    queryFn: () => api.checkpoints(id!),
    enabled: Boolean(id),
  });

export function useCreateRun() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: api.createRun,
    onSuccess: () => client.invalidateQueries({ queryKey: keys.runs }),
  });
}

export function useRunControl(runId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { action: "START" | "PAUSE" | "RESUME" | "STOP" | "STEP"; ticks?: number }) =>
      api.control(runId, input.action, input.ticks),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: keys.run(runId) });
      client.invalidateQueries({ queryKey: keys.runs });
    },
  });
}

export function useSetSpeed(runId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (ticksPerSecond: number) => api.setSpeed(runId, ticksPerSecond),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.run(runId) }),
  });
}

export function useCheckpoint(runId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (label: string | null) => api.checkpoint(runId, label),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.checkpoints(runId) }),
  });
}

export function useRestore(runId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { checkpointId: string; fork: boolean; name?: string }) =>
      api.restore(runId, input.checkpointId, input.fork, input.name),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: keys.runs });
      client.invalidateQueries({ queryKey: keys.run(runId) });
      client.invalidateQueries({ queryKey: keys.forks(runId) });
    },
  });
}

export function useDeleteRun() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.deleteRun(id),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.runs }),
  });
}

// --- observability ------------------------------------------------------------------------------

export const useSeriesCatalogue = (id: string | undefined) =>
  useQuery({
    queryKey: keys.seriesCatalogue(id ?? ""),
    queryFn: () => api.seriesCatalogue(id!),
    enabled: Boolean(id),
    refetchInterval: 5000,
  });

export const useSeriesData = (
  id: string | undefined,
  seriesKeys: string[],
  range: { from: number; to: number; resolution: number },
  refetchInterval: number | false,
) =>
  useQuery({
    queryKey: keys.seriesData(id ?? "", seriesKeys, range.from, range.to, range.resolution),
    queryFn: () => api.seriesData(id!, seriesKeys, range.from, range.to, range.resolution),
    enabled: Boolean(id) && seriesKeys.length > 0,
    refetchInterval,
    // Keeping the previous data while a wider range loads stops the chart flashing empty on zoom.
    placeholderData: (previous) => previous,
  });

export const useRunLog = (
  id: string | undefined,
  type: string | undefined,
  limit: number,
  refetchInterval: number | false,
) =>
  useQuery({
    queryKey: keys.log(id ?? "", type, limit),
    queryFn: () => api.log(id!, { type, limit }),
    enabled: Boolean(id),
    refetchInterval,
  });

export const useMemories = (id: string | undefined, ownerId: string | undefined) =>
  useQuery({
    queryKey: keys.memories(id ?? "", ownerId),
    queryFn: () => api.memories(id!, ownerId),
    enabled: Boolean(id),
  });

export const useRelationships = (id: string | undefined) =>
  useQuery({
    queryKey: keys.relationships(id ?? ""),
    queryFn: () => api.relationships(id!),
    enabled: Boolean(id),
  });
