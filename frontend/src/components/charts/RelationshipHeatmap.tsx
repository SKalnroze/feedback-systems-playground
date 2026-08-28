import { useECharts, type EChartsOption } from "@/components/charts/useECharts";
import type { RelationshipCell } from "@/api/types";
import { cssVar, formatNumber } from "@/lib/utils";
import { useMemo } from "react";

/**
 * Who currently thinks what of whom.
 *
 * A diverging scale, because the quantity has a meaningful zero: "no strong feeling" is a real
 * state, not the bottom of a range, and it has to look different from "actively negative". Warm
 * and cool poles with a neutral grey midpoint say that; a single-hue ramp would not.
 *
 * The matrix is read row-first: the row is who holds the view, the column is who it is about.
 * Those are not the same thing, and a model where they can differ is the point of the exercise.
 */
export function RelationshipHeatmap({
  cells,
  theme,
  height = 320,
  onSelect,
}: {
  cells: RelationshipCell[];
  theme: string;
  height?: number;
  onSelect?: (ownerId: string, subjectId: string) => void;
}) {
  const { option, people } = useMemo(() => {
    const ids = Array.from(new Set(cells.flatMap((cell) => [cell.ownerId, cell.subjectId]))).sort();
    if (!ids.length) return { option: null, people: ids };

    const textMuted = cssVar("--text-muted", "#888");
    const textPrimary = cssVar("--text-primary", "#111");
    const textSecondary = cssVar("--text-secondary", "#555");
    const surface = cssVar("--surface-1", "#fff");
    const surface2 = cssVar("--surface-2", "#f4f3f0");
    const border = cssVar("--border", "#ddd");
    const negative = cssVar("--diverging-negative", "#e34948");
    const neutral = cssVar("--diverging-neutral", "#f0efec");
    const positive = cssVar("--diverging-positive", "#2a78d6");

    const byPair = new Map(cells.map((cell) => [`${cell.ownerId}|${cell.subjectId}`, cell]));
    const data: [number, number, number][] = [];
    ids.forEach((owner, row) => {
      ids.forEach((subject, column) => {
        if (owner === subject) return;
        const cell = byPair.get(`${owner}|${subject}`);
        data.push([column, row, cell ? cell.meanValence : 0]);
      });
    });

    return {
      people: ids,
      option: {
        animation: false,
        backgroundColor: "transparent",
        grid: { left: 78, right: 24, top: 28, bottom: 52 },
        tooltip: {
          backgroundColor: surface,
          borderColor: border,
          borderWidth: 1,
          textStyle: { color: textPrimary, fontSize: 12 },
          formatter: (params: unknown) => {
            const point = params as { value: [number, number, number] };
            const owner = ids[point.value[1]];
            const subject = ids[point.value[0]];
            const cell = byPair.get(`${owner}|${subject}`);
            if (!cell) return `${owner} has no memories of ${subject}`;
            return `<div style="color:${textSecondary}">${owner} &rarr; ${subject}</div>
              <div style="margin-top:4px;font-variant-numeric:tabular-nums">
                feeling ${formatNumber(cell.meanValence)}<br/>
                held in ${cell.memoryCount} ${cell.memoryCount === 1 ? "memory" : "memories"}<br/>
                total strength ${formatNumber(cell.totalStrength)}
              </div>`;
          },
        },
        xAxis: {
          type: "category",
          data: ids,
          name: "about",
          nameLocation: "middle",
          nameGap: 32,
          nameTextStyle: { color: textMuted, fontSize: 10 },
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: textMuted, fontSize: 11, rotate: ids.length > 6 ? 30 : 0 },
          splitArea: { show: false },
        },
        yAxis: {
          type: "category",
          data: ids,
          name: "held by",
          nameLocation: "middle",
          nameGap: 62,
          nameTextStyle: { color: textMuted, fontSize: 10 },
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: textMuted, fontSize: 11 },
          splitArea: { show: false },
        },
        visualMap: {
          min: -1,
          max: 1,
          calculable: true,
          orient: "horizontal",
          left: "center",
          bottom: 4,
          itemWidth: 10,
          itemHeight: 90,
          textStyle: { color: textMuted, fontSize: 10 },
          text: ["warm", "cold"],
          inRange: { color: [negative, neutral, positive] },
        },
        series: [
          {
            type: "heatmap",
            data,
            // A 2px gap between cells: adjacent fills of similar value would otherwise merge.
            itemStyle: { borderColor: surface2, borderWidth: 2, borderRadius: 3 },
            emphasis: { itemStyle: { borderColor: textSecondary, borderWidth: 2 } },
            progressive: 0,
          },
        ],
      } as EChartsOption,
    };
  }, [cells, theme]);

  const { ref } = useECharts(option, [option], {
    onClick: (params) => {
      const value = params.value as [number, number, number] | undefined;
      if (!value || !onSelect) return;
      const owner = people[value[1]];
      const subject = people[value[0]];
      if (owner && subject) onSelect(owner, subject);
    },
  });

  if (!option) {
    return (
      <div
        className="flex items-center justify-center text-xs text-[var(--text-muted)]"
        style={{ height }}
      >
        Nobody remembers anything yet.
      </div>
    );
  }

  return <div ref={ref} style={{ height }} role="img" aria-label="Relationship matrix" />;
}
