import { useECharts, type EChartsOption } from "@/components/charts/useECharts";
import type { Distribution } from "@/api/types";
import { cssVar, formatNumber, seriesColorValue } from "@/lib/utils";
import { useMemo } from "react";

/**
 * How a group's members are spread across one variable.
 *
 * The chart answers the question a mean cannot. A population sitting uniformly at 0.5 and one split
 * evenly between 0 and 1 report the same average and the same line; only here do they look
 * different, and the difference is usually the finding.
 *
 * The mean is drawn as a rule across the bars so the reader can see where it falls in the spread —
 * a mean sitting in an empty valley between two clusters is the clearest possible statement that
 * the average describes nobody.
 */
export function DistributionChart({
  distribution,
  theme,
  height = 220,
}: {
  distribution: Distribution;
  theme: string;
  height?: number;
}) {
  const option = useMemo<EChartsOption | null>(() => {
    if (!distribution.buckets.length) return null;

    const textMuted = cssVar("--text-muted", "#888");
    const textPrimary = cssVar("--text-primary", "#111");
    const textSecondary = cssVar("--text-secondary", "#555");
    const grid = cssVar("--grid", "#eee");
    const axis = cssVar("--axis", "#ccc");
    const surface = cssVar("--surface-1", "#fff");
    const border = cssVar("--border", "#ddd");
    const colour = seriesColorValue(`${distribution.groupId}.${distribution.variable}`);

    return {
      animation: false,
      backgroundColor: "transparent",
      grid: { left: 44, right: 16, top: 24, bottom: 34 },
      tooltip: {
        trigger: "axis",
        axisPointer: { type: "shadow" },
        backgroundColor: surface,
        borderColor: border,
        borderWidth: 1,
        textStyle: { color: textPrimary, fontSize: 12 },
        formatter: (params: unknown) => {
          const bars = params as { dataIndex: number; value: number }[];
          const first = bars[0];
          if (!first) return "";
          const bucket = distribution.buckets[first.dataIndex];
          if (!bucket) return "";
          const share = distribution.members
            ? Math.round((bucket.count / distribution.members) * 100)
            : 0;
          return `<div style="color:${textSecondary}">${formatNumber(bucket.from, 3)} – ${formatNumber(
            bucket.to,
            3,
          )}</div><div><b>${bucket.count}</b> of ${distribution.members} (${share}%)</div>`;
        },
      },
      xAxis: {
        type: "category",
        // Only a handful of edge labels: twenty-four printed values is unreadable and the tooltip
        // gives the exact range for any bar the reader cares about.
        data: distribution.buckets.map((bucket) => formatNumber(bucket.from, 2)),
        axisLine: { lineStyle: { color: axis } },
        axisTick: { show: false },
        axisLabel: { color: textMuted, fontSize: 10, hideOverlap: true },
        name: distribution.variable,
        nameLocation: "end",
        nameGap: 6,
        nameTextStyle: { color: textMuted, fontSize: 10 },
      },
      yAxis: {
        type: "value",
        name: "members",
        nameTextStyle: { color: textMuted, fontSize: 10, align: "left" },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: textMuted, fontSize: 10 },
        splitLine: { lineStyle: { color: grid } },
      },
      series: [
        {
          type: "bar",
          data: distribution.buckets.map((bucket) => bucket.count),
          itemStyle: { color: colour, borderRadius: [2, 2, 0, 0] },
          barCategoryGap: "12%",
          markLine: {
            silent: true,
            symbol: "none",
            data: [
              {
                // A category axis matches its marks by category *value*, not by index. Passing the
                // index drew nothing at all — the whole chart came out blank rather than merely
                // missing its mean rule, which is why this is the label string.
                xAxis: categoryFor(distribution),
                label: {
                  formatter: `mean ${formatNumber(distribution.mean, 3)}`,
                  color: textSecondary,
                  fontSize: 10,
                  position: "insideEndTop",
                },
                lineStyle: { color: textSecondary, type: "dashed", width: 1 },
              },
            ],
          },
        },
      ],
    } as EChartsOption;
  }, [distribution, theme]);

  const { ref } = useECharts(option, [option]);

  if (!distribution.buckets.length) {
    return (
      <div
        className="flex items-center justify-center text-xs text-[var(--text-muted)]"
        style={{ height }}
      >
        This object is a single instance, so it has no distribution — chart its value instead.
      </div>
    );
  }

  return <div ref={ref} style={{ height }} role="img" aria-label="Distribution across members" />;
}

/** The axis category the mean falls in, matching the labels put on the x-axis. */
function categoryFor(distribution: Distribution): string {
  const bucket =
    distribution.buckets.find((slot) => distribution.mean >= slot.from && distribution.mean <= slot.to) ??
    distribution.buckets[0];
  return formatNumber(bucket?.from ?? 0, 2);
}
