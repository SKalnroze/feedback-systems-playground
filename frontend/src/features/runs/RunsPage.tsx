import type { RunStatus, RunSummary } from "@/api/types";
import {
  useCreateRun,
  useDeleteRun,
  useRuns,
  useSystems,
  useVersions,
} from "@/api/queries";
import { api } from "@/api/client";
import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  ErrorNote,
  Field,
  Input,
  Select,
  Spinner,
} from "@/components/ui/primitives";
import { formatTick } from "@/lib/utils";
import { Link } from "@tanstack/react-router";
import { useState } from "react";

const STATUS_TONE: Record<RunStatus, "neutral" | "good" | "warning" | "critical" | "accent"> = {
  CREATED: "neutral",
  RUNNING: "good",
  PAUSED: "warning",
  STOPPED: "neutral",
  COMPLETED: "accent",
  FAILED: "critical",
};

/** The list of runs, and the form for starting a new one. */
export function RunsPage() {
  const runs = useRuns();
  const deleteRun = useDeleteRun();

  return (
    <div className="mx-auto grid max-w-6xl gap-4 p-4 lg:grid-cols-[1fr_320px]">
      <Card className="min-w-0">
        <CardHeader
          title="Runs"
          subtitle="Simulations you have started. They keep going in the background."
          actions={runs.isFetching ? <Spinner /> : null}
        />
        {runs.isLoading ? (
          <div className="p-6 text-sm text-[var(--text-muted)]">Loading runs…</div>
        ) : runs.data && runs.data.length > 0 ? (
          <ul className="divide-y divide-[var(--border)]">
            {runs.data.map((run) => (
              <RunRow key={run.id} run={run} onDelete={() => deleteRun.mutate(run.id)} />
            ))}
          </ul>
        ) : (
          <EmptyState
            title="No runs yet"
            description="Publish a system, then start a run against it. A run holds its own seed, so it can always be repeated exactly."
          />
        )}
      </Card>

      <NewRunPanel />
    </div>
  );
}

function RunRow({ run, onDelete }: { run: RunSummary; onDelete: () => void }) {
  return (
    <li className="flex items-center gap-3 px-4 py-3">
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <Link
            to="/runs/$runId"
            params={{ runId: run.id }}
            className="truncate text-sm font-medium hover:underline"
          >
            {run.name}
          </Link>
          <Badge tone={STATUS_TONE[run.status]}>{run.status.toLowerCase()}</Badge>
          {run.parentRunId ? (
            <Badge tone="neutral" title={`forked at tick ${run.forkedFromTick}`}>
              fork @{run.forkedFromTick}
            </Badge>
          ) : null}
          {run.watchers > 0 ? <Badge tone="accent">{run.watchers} watching</Badge> : null}
        </div>
        <div className="mt-0.5 truncate text-xs text-[var(--text-muted)]">
          {run.systemName} · seed <span className="tabular">{run.seed}</span>
        </div>
        {run.error ? <div className="mt-1 text-xs text-[var(--status-critical)]">{run.error}</div> : null}
      </div>

      <div className="tabular shrink-0 text-right">
        <div className="text-sm font-medium">{formatTick(run.tick)}</div>
        <div className="text-xs text-[var(--text-muted)]">ticks</div>
      </div>

      <Button variant="ghost" size="sm" onClick={onDelete} title="Delete this run and its data">
        Delete
      </Button>
    </li>
  );
}

/**
 * Starting a run.
 *
 * The seed is offered explicitly rather than hidden: repeating a run exactly is the whole basis of
 * comparing one configuration against another, and that only works if the seed is something the
 * user can see and set.
 */
function NewRunPanel() {
  const systems = useSystems();
  const createRun = useCreateRun();

  const [systemId, setSystemId] = useState("");
  const [versionId, setVersionId] = useState("");
  const [name, setName] = useState("");
  const [seed, setSeed] = useState("");
  const [speed, setSpeed] = useState("10");
  const [autoStart, setAutoStart] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const versions = useVersions(systemId || undefined);

  const publishedSystems = systems.data ?? [];

  async function submit() {
    setError(null);
    try {
      let chosenVersion = versionId;
      if (!chosenVersion && systemId) {
        chosenVersion = (await api.latestVersion(systemId)).id;
      }
      if (!chosenVersion) {
        setError("Pick a system with at least one published version.");
        return;
      }
      await createRun.mutateAsync({
        systemVersionId: chosenVersion,
        name: name || undefined,
        seed: seed || null,
        speed: speed ? Number(speed) : null,
        autoStart,
      });
      setName("");
      setSeed("");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not start the run.");
    }
  }

  return (
    <Card className="h-fit">
      <CardHeader title="Start a run" subtitle="Against a published version of a system" />
      <div className="space-y-3 p-4">
        <Field label="System">
          <Select
            className="w-full"
            value={systemId}
            onChange={(event) => {
              setSystemId(event.target.value);
              setVersionId("");
            }}
          >
            <option value="">Choose a system…</option>
            {publishedSystems.map((system) => (
              <option key={system.id} value={system.id}>
                {system.name}
              </option>
            ))}
          </Select>
        </Field>

        {versions.data && versions.data.length > 0 ? (
          <Field label="Version" hint="Runs are pinned to a version, so later edits cannot change them.">
            <Select
              className="w-full"
              value={versionId}
              onChange={(event) => setVersionId(event.target.value)}
            >
              <option value="">Latest (v{versions.data[0]?.version})</option>
              {versions.data.map((version) => (
                <option key={version.id} value={version.id}>
                  v{version.version}
                </option>
              ))}
            </Select>
          </Field>
        ) : systemId ? (
          <ErrorNote message="This system has no published versions yet. Open it and publish first." />
        ) : null}

        <Field label="Name" hint="Optional. Defaults to the system's name.">
          <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="Baseline" />
        </Field>

        <div className="grid grid-cols-2 gap-3">
          <Field label="Seed" hint="Blank for random">
            <Input
              className="tabular"
              value={seed}
              onChange={(event) => setSeed(event.target.value.replace(/[^\d-]/g, ""))}
              placeholder="random"
              inputMode="numeric"
            />
          </Field>
          <Field label="Ticks / second" hint="0 for as fast as possible">
            <Input
              className="tabular"
              value={speed}
              onChange={(event) => setSpeed(event.target.value.replace(/[^\d.]/g, ""))}
              inputMode="decimal"
            />
          </Field>
        </div>

        <label className="flex items-center gap-2 text-sm text-[var(--text-secondary)]">
          <input
            type="checkbox"
            checked={autoStart}
            onChange={(event) => setAutoStart(event.target.checked)}
          />
          Start immediately
        </label>

        {error ? <ErrorNote message={error} /> : null}

        <Button variant="primary" className="w-full" onClick={submit} disabled={createRun.isPending}>
          {createRun.isPending ? "Starting…" : "Start run"}
        </Button>
      </div>
    </Card>
  );
}
