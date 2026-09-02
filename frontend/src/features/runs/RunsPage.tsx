import type { RunStatus, RunSummary, SeriesDefinition } from "@/api/types";
import {
  useCreateRun,
  useDescribeRun,
  useDeleteRun,
  useRuns,
  useSeriesCatalogue,
  useSeriesData,
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
  Skeleton,
  Spinner,
} from "@/components/ui/primitives";
import { ConfirmButton } from "@/components/ui/ConfirmButton";
import { EditableDescription } from "@/components/ui/EditableDescription";
import { Sparkline } from "@/components/charts/Sparkline";
import { formatTick, seriesColorValue } from "@/lib/utils";
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
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState<string>("");
  const [system, setSystem] = useState<string>("");
  const [order, setOrder] = useState<"recent" | "tick" | "name">("recent");

  const all = runs.data ?? [];
  const systems = [...new Set(all.map((run) => run.systemName))].sort();

  const needle = search.trim().toLowerCase();
  const visible = all
    .filter((run) => (status ? run.status === status : true))
    .filter((run) => (system ? run.systemName === system : true))
    .filter(
      (run) =>
        needle === "" ||
        run.name.toLowerCase().includes(needle) ||
        run.description.toLowerCase().includes(needle) ||
        run.systemName.toLowerCase().includes(needle),
    )
    .sort((left, right) => {
      if (order === "tick") return right.tick - left.tick;
      if (order === "name") return left.name.localeCompare(right.name);
      return 0; // the API already returns newest first
    });

  return (
    <div className="mx-auto grid max-w-6xl gap-4 p-4 lg:grid-cols-[1fr_320px]">
      <Card className="min-w-0">
        <CardHeader
          title="Runs"
          subtitle="Simulations you have started. They keep going in the background."
          actions={runs.isFetching ? <Spinner /> : null}
        />
        <div className="flex flex-wrap items-center gap-2 border-b border-[var(--border)] px-3 py-2">
          <Input
            className="h-7 w-40 text-xs"
            placeholder="Search runs…"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            aria-label="Search runs"
          />
          <Select
            className="h-7 text-xs"
            value={status}
            onChange={(event) => setStatus(event.target.value)}
            aria-label="Filter by status"
          >
            <option value="">any status</option>
            {["RUNNING", "PAUSED", "CREATED", "STOPPED", "COMPLETED", "FAILED"].map((option) => (
              <option key={option} value={option}>
                {option.toLowerCase()}
              </option>
            ))}
          </Select>
          <Select
            className="h-7 text-xs"
            value={system}
            onChange={(event) => setSystem(event.target.value)}
            aria-label="Filter by system"
          >
            <option value="">any system</option>
            {systems.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </Select>
          <Select
            className="h-7 text-xs"
            value={order}
            onChange={(event) => setOrder(event.target.value as typeof order)}
            aria-label="Sort runs"
          >
            <option value="recent">newest first</option>
            <option value="tick">furthest along</option>
            <option value="name">by name</option>
          </Select>
          {visible.length !== all.length ? (
            <span className="text-xs text-[var(--text-muted)]">
              {visible.length} of {all.length}
            </span>
          ) : null}
        </div>

        {runs.isLoading ? (
          <Skeleton rows={5} />
        ) : visible.length > 0 ? (
          <ul className="divide-y divide-[var(--border)]">
            {visible.map((run) => (
              <RunRow key={run.id} run={run} onDelete={() => deleteRun.mutate(run.id)} />
            ))}
          </ul>
        ) : all.length > 0 ? (
          // Distinguishing "nothing matches your filters" from "you have no runs" matters: one is
          // solved by clearing a filter and the other by starting a run.
          <EmptyState
            title="No runs match those filters"
            description="Clear the search or the filters above to see the rest."
          />
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
  const describe = useDescribeRun(run.id);
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
        <EditableDescription
          value={run.description}
          onSave={(description) => describe.mutate(description)}
          placeholder="What is this run for?"
        />
        {run.error ? <div className="mt-1 text-xs text-[var(--status-critical)]">{run.error}</div> : null}
      </div>

      <RunSparkline run={run} />

      <div className="tabular shrink-0 text-right">
        <div className="text-sm font-medium">{formatTick(run.tick)}</div>
        <div className="text-xs text-[var(--text-muted)]">ticks</div>
      </div>

      <ConfirmButton onConfirm={onDelete} title="Delete this run, its samples, log and checkpoints" />
    </li>
  );
}

/**
 * A thumbnail of one series from a run, so a list of forks can be read rather than opened.
 *
 * Forks of one system all carry the same name and the same system, and the only thing that
 * distinguishes them is what they did — which until now could not be seen without opening each one
 * in turn. One line is not the run, and it is not offered as one: it is enough to see which of a
 * dozen siblings went somewhere different, which is the question a list of forks actually raises.
 */
function RunSparkline({ run }: { run: RunSummary }) {
  // Nothing polls here. A list row is a summary, not a live view, and the run page is one click away.
  const catalogue = useSeriesCatalogue(run.tick > 0 ? run.id : undefined, false);
  const key = representativeSeries(catalogue.data ?? []);
  const data = useSeriesData(
    run.tick > 0 ? run.id : undefined,
    key ? [key] : [],
    // Auto resolution: the server buckets a long run down, so a ten-thousand-tick run costs the
    // same handful of points as a short one.
    { from: 0, to: -1, resolution: 0 },
    false,
  );

  const points = data.data?.series?.[0]?.points ?? [];
  if (!key || points.length < 2) {
    return <div className="hidden w-24 shrink-0 sm:block" aria-hidden />;
  }

  return (
    <div className="hidden w-24 shrink-0 sm:block" title={`${key} over the whole run`}>
      <Sparkline points={points} width={96} height={24} colour={seriesColorValue(key)} label={key} />
    </div>
  );
}

/**
 * Which of a run's series to sketch.
 *
 * A group's mean first, then any object variable, then whatever comes first alphabetically. Sorted
 * before choosing so that the same run always draws the same line — a thumbnail that changed
 * variable between visits would be worse than none.
 */
function representativeSeries(entries: SeriesDefinition[]): string | null {
  const ordered = [...entries].sort((a, b) => a.seriesKey.localeCompare(b.seriesKey));
  return (
    ordered.find((entry) => entry.seriesKey.endsWith(".mean"))?.seriesKey ??
    ordered.find((entry) => entry.objectId && entry.variable)?.seriesKey ??
    ordered[0]?.seriesKey ??
    null
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
  const [description, setDescription] = useState("");
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
        description: description || undefined,
        seed: seed || null,
        speed: speed ? Number(speed) : null,
        autoStart,
      });
      setName("");
      setSeed("");
      setDescription("");
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

        <Field label="What is it for?" hint="Optional. Shown in the run list to tell forks apart.">
          <Input
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="Does the rumour die out at this gossip rate?"
          />
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
