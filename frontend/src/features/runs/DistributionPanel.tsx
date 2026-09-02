import type { SystemSpec } from "@/api/types";
import { useDistribution } from "@/api/queries";
import { DistributionChart } from "@/components/charts/DistributionChart";
import { PanelBody, panelPhase } from "@/components/ui/PanelBody";
import { Card, CardHeader, Field, Select, Spinner } from "@/components/ui/primitives";
import { formatTick } from "@/lib/utils";
import { DEFAULT_FORMAT, formatValue, type ValueFormat } from "@/lib/valueFormat";
import { useEffect, useMemo, useState } from "react";

/**
 * What a group's average is hiding.
 *
 * The charts show a band, which says how wide the spread is; this says what shape it has. A band
 * from 0 to 1 looks identical whether the population is evenly smeared across that range or piled
 * at both ends with nobody in the middle, and those describe completely different situations.
 */
export function DistributionPanel({
  runId,
  spec,
  live,
  theme,
  tick,
  format = DEFAULT_FORMAT,
}: {
  runId: string;
  spec: SystemSpec | null;
  live: boolean;
  theme: string;
  tick: number;
  format?: ValueFormat;
}) {
  // Only objects standing for more than one instance have a distribution at all.
  const groups = useMemo(() => (spec?.objects ?? []).filter((object) => object.count > 1), [spec]);
  const [groupId, setGroupId] = useState<string>("");
  const [variable, setVariable] = useState<string>("");

  const group = groups.find((candidate) => candidate.id === groupId) ?? groups[0];
  const type = spec?.objectTypes.find((candidate) => candidate.id === group?.typeId);
  const variables = type?.variables ?? [];

  // Default to the first group and variable so the panel opens on something rather than on two
  // empty pickers the reader has to fill in before seeing anything.
  useEffect(() => {
    if (!groupId && groups[0]) setGroupId(groups[0].id);
  }, [groups, groupId]);
  useEffect(() => {
    if (!variable && variables[0]) setVariable(variables[0].name);
  }, [variables, variable]);

  const distribution = useDistribution(runId, group?.id, variable || undefined, live);

  if (groups.length === 0) {
    return (
      <Card>
        <CardHeader title="Distribution" subtitle="Across the members of a group" />
        <div className="p-6 text-sm text-[var(--text-muted)]">
          This system has no groups — every object stands for one instance, so there is nothing to
          spread. Give an object a count above one to model a population.
        </div>
      </Card>
    );
  }

  const data = distribution.data;

  return (
    <Card>
      <CardHeader
        title="Distribution"
        subtitle={
          data
            ? `${data.members.toLocaleString()} members at tick ${formatTick(data.tick)}`
            : "Across the members of a group"
        }
        actions={distribution.isFetching ? <Spinner /> : null}
      />

      <div className="flex flex-wrap items-end gap-3 px-4 pt-3">
        <Field label="Group">
          <Select value={group?.id ?? ""} onChange={(event) => setGroupId(event.target.value)}>
            {groups.map((candidate) => (
              <option key={candidate.id} value={candidate.id}>
                {candidate.label} (×{candidate.count.toLocaleString()})
              </option>
            ))}
          </Select>
        </Field>
        <Field label="Variable">
          <Select value={variable} onChange={(event) => setVariable(event.target.value)}>
            {variables.map((candidate) => (
              <option key={candidate.name} value={candidate.name}>
                {candidate.label || candidate.name}
              </option>
            ))}
          </Select>
        </Field>

        {data ? (
          <div className="flex gap-4 text-xs text-[var(--text-muted)]">
            <span>
              lowest <span className="tabular text-[var(--text-secondary)]">{formatValue(data.min, format)}</span>
            </span>
            <span>
              mean <span className="tabular text-[var(--text-secondary)]">{formatValue(data.mean, format)}</span>
            </span>
            <span>
              highest <span className="tabular text-[var(--text-secondary)]">{formatValue(data.max, format)}</span>
            </span>
          </div>
        ) : null}
      </div>

      <div className="p-2">
        <PanelBody
          phase={panelPhase(distribution, (data?.buckets.length ?? 0) === 0)}
          error={distribution.error}
          height={240}
          emptyTitle={data && data.members === 0 ? "Not available for a finished run" : "Nothing to show yet"}
          emptyDescription={
            data && data.members === 0
              ? "A distribution is a fact about a run's live state, and a stopped run no longer has one. Its charts and log still hold everything it recorded."
              : "Step the run so its members have values to spread out across."
          }
        >
          {data ? <DistributionChart distribution={data} theme={theme} /> : null}
        </PanelBody>
      </div>

      <p className="px-4 pb-3 text-xs text-[var(--text-muted)]">
        The current state of the run, not tick {formatTick(tick)} of its history: individual members are
        not recorded over time, only the group's statistics. Step the run to watch the shape change.
      </p>
    </Card>
  );
}
