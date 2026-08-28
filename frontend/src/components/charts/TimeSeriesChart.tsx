import { useECharts, type EChartsOption } from "@/components/charts/useECharts";
import type { SeriesView } from "@/api/types";
import { cssVar, formatNumber, seriesColor, seriesLabel } from "@/lib/utils";
import { useMemo } from "react";

export type TimeSeriesChartProps = {
  series: SeriesView[];
  /** Redraws when the theme changes, since colours are read from CSS at draw time. */
  theme: string;
  height?: number;
  /** Ticks to mark with a vertical rule, e.g. where an event fired or a checkpoint was taken. */
  markers?: { tick: number; label: string }[];
  /** Shared x-range, so several stacked panels stay aligned while one is zoomed. */
  range?: { from: number; to: number } | null;
  onRangeChange?: (range: { from: number; to: number } | null) => void;
  onTickClick?: (tick: number) => void;
  yMin?: number;
  yMax?: number;
};

/**
 * Multi-series line chart over simulation ticks.
 *
 * Every series shares one y-axis on purpose. Two axes at different scales is the single most
 * misleading thing a chart of this kind can do - it lets any two lines be made to appear to move
 * together - so variables on different scales belong in separate panels, which this component is
 * designed to be stacked into.
 */
export function TimeSeriesChart({
  series,
  theme,
  height = 260,
  markers = [],
  range = null,
  onRangeChange,
  onTickClick,
  yMin,
  yMax,
}: TimeSeriesChartProps) {
  const option = useMemo<EChartsOption | null>(() => {
    if (!series.length) return null;

    const textPrimary = cssVar("--text-primary", "#111");
    const textSecondary = cssVar("--text-secondary", "#555");
    const textMuted = cssVar("--text-muted", "#888");
    const grid = cssVar("--grid", "#eee");
    const axis = cssVar("--axis", "#ccc");
    const surface = cssVar("--surface-1", "#fff");
    const border = cssVar("--border", "#ddd");

    // Four or fewer series are also labelled at their last point, so identity never rests on
    // colour alone. Beyond four the labels collide and the legend carries it.
    const directLabels = series.length <= 4;

    return {
      animation: false,
      backgroundColor: "transparent",
      grid: {
        left: 52,
        right: directLabels ? 92 : 20,
        top: series.length > 1 ? 34 : 16,
        bottom: 34,
      },
      legend:
        series.length > 1
          ? {
              type: "scroll",
              top: 0,
              left: 0,
              itemWidth: 12,
              itemHeight: 2,
              itemGap: 14,
              textStyle: { color: textSecondary, fontSize: 11 },
              inactiveColor: textMuted,
            }
          : undefined,
      tooltip: {
        trigger: "axis",
        axisPointer: { type: "line", lineStyle: { color: axis, width: 1 } },
        backgroundColor: surface,
        borderColor: border,
        borderWidth: 1,
        padding: [6, 10],
        textStyle: { color: textPrimary, fontSize: 12 },
        formatter: (params: unknown) => {
          const points = params as { axisValue: number; seriesName: string; data: [number, number]; color: string }[];
          if (!points.length) return "";
          const head = `<div style="color:${textMuted};font-size:11px;margin-bottom:4px">tick ${points[0]?.axisValue}</div>`;
          const rows = points
            .map(
              (point) =>
                `<div style="display:flex;gap:8px;align-items:center;justify-content:space-between">
                   <span style="display:flex;gap:6px;align-items:center">
                     <span style="width:8px;height:8px;border-radius:2px;background:${point.color}"></span>
                     <span style="color:${textSecondary}">${seriesLabel(point.seriesName)}</span>
                   </span>
                   <span style="font-variant-numeric:tabular-nums">${formatNumber(point.data[1])}</span>
                 </div>`,
            )
            .join("");
          return head + rows;
        },
      },
      xAxis: {
        type: "value",
        name: "tick",
        nameLocation: "end",
        nameGap: 8,
        nameTextStyle: { color: textMuted, fontSize: 10 },
        min: range?.from ?? "dataMin",
        max: range?.to ?? "dataMax",
        axisLine: { lineStyle: { color: axis } },
        axisTick: { show: false },
        axisLabel: { color: textMuted, fontSize: 11, hideOverlap: true },
        splitLine: { show: false },
      },
      yAxis: {
        type: "value",
        min: yMin ?? "dataMin",
        max: yMax ?? "dataMax",
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: textMuted, fontSize: 11 },
        // Recessive grid: present enough to read a value off, quiet enough to stay behind the data.
        splitLine: { lineStyle: { color: grid, width: 1 } },
      },
      dataZoom: [
        { type: "inside", filterMode: "none" },
        {
          type: "slider",
          height: 16,
          bottom: 4,
          borderColor: border,
          fillerColor: `${axis}33`,
          handleStyle: { color: axis },
          textStyle: { color: textMuted, fontSize: 10 },
          filterMode: "none",
        },
      ],
      series: series.map((entry) => ({
        name: entry.key,
        type: "line",
        data: entry.points,
        showSymbol: false,
        symbolSize: 8,
        // Thin marks; the data is the ink.
        lineStyle: { width: 2, color: seriesColor(entry.key) },
        itemStyle: { color: seriesColor(entry.key) },
        emphasis: { focus: "series", lineStyle: { width: 2.5 } },
        // Largest-triangle sampling keeps the shape of a long series while drawing far fewer
        // points than it contains.
        sampling: "lttb",
        large: true,
        largeThreshold: 2000,
        endLabel: directLabels
          ? {
              show: true,
              formatter: (params: { seriesName: string }) => seriesLabel(params.seriesName),
              color: textSecondary,
              fontSize: 11,
              distance: 6,
            }
          : { show: false },
        markLine: markers.length
          ? {
              silent: true,
              symbol: "none",
              label: {
                formatter: (params: { name: string }) => params.name,
                color: textMuted,
                fontSize: 10,
                position: "insideEndTop",
              },
              lineStyle: { color: axis, type: "dashed", width: 1 },
              data: markers.map((marker) => ({ xAxis: marker.tick, name: marker.label })),
            }
          : undefined,
      })),
    } as EChartsOption;
  }, [series, theme, markers, range, yMin, yMax]);

  const { ref, instance } = useECharts(option, [option], {
    onClick: (params) => {
      const value = params.value as [number, number] | undefined;
      if (value && onTickClick) onTickClick(value[0]);
    },
  });

  // Zooming one panel adjusts the shared range, which the other panels then follow.
  useMemo(() => {
    const chart = instance.current;
    if (!chart || !onRangeChange) return;
    chart.off("datazoom");
    chart.on("datazoom", () => {
      const axes = (chart.getOption() as { xAxis?: { min?: number; max?: number }[] }).xAxis;
      const first = axes?.[0];
      if (first && typeof first.min === "number" && typeof first.max === "number") {
        onRangeChange({ from: first.min, to: first.max });
      }
    });
  }, [instance, onRangeChange]);

  if (!series.length) {
    return (
      <div
        className="flex items-center justify-center text-xs text-[var(--text-muted)]"
        style={{ height }}
      >
        Pick one or more series to chart.
      </div>
    );
  }

  return <div ref={ref} style={{ height }} role="img" aria-label="Time series chart" />;
}
