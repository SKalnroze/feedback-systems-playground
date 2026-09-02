import { useECharts, type EChartsOption } from "@/components/charts/useECharts";
import type { SeriesView } from "@/api/types";
import { cssVar, seriesColorValue, seriesLabel } from "@/lib/utils";
import { DEFAULT_FORMAT, formatValue, type ValueFormat } from "@/lib/valueFormat";
import type { SeriesBand } from "@/features/runs/seriesTransforms";
import { useEffect, useMemo } from "react";

/**
 * Which palette slot each kind of log entry gets on the event strip.
 *
 * Slot numbers rather than `var(--series-n)` strings: ECharts draws to a canvas and cannot resolve a
 * CSS custom property, so a token handed to it is silently discarded and the mark comes out grey.
 * The slot is resolved through `seriesColorValue` at draw time, where the theme is readable.
 */
const EVENT_SLOT: Record<string, number> = { EVENT: 1, CHECKPOINT: 2, NOTE: 3 };

export type TimeSeriesChartProps = {
  series: SeriesView[];
  /** Groups drawn as a mean line inside the range their members occupied. */
  bands?: SeriesBand[];
  /** What the y-axis measures, including any active transform. */
  axisLabel?: string;
  /** Where events, checkpoints and notes happened, drawn as a strip under the plot. */
  events?: { tick: number; type: string; detail: string }[];
  /** Colours chosen by the reader, by series key, overriding the palette slot. */
  colours?: Record<string, string>;
  /** How values are written, so the tooltip and the tables beside it agree. */
  format?: ValueFormat;
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
  bands = [],
  axisLabel,
  events = [],
  colours = {},
  format = DEFAULT_FORMAT,
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
    if (!series.length && !bands.length) return null;

    const textPrimary = cssVar("--text-primary", "#111");
    const textSecondary = cssVar("--text-secondary", "#555");
    const textMuted = cssVar("--text-muted", "#888");
    const grid = cssVar("--grid", "#eee");
    const axis = cssVar("--axis", "#ccc");
    const surface = cssVar("--surface-1", "#fff");
    const border = cssVar("--border", "#ddd");

    // Four or fewer series are also labelled at their last point, so identity never rests on
    // colour alone. Beyond four the labels collide and the legend carries it.
    const directLabels = series.length + (bands?.length ?? 0) <= 4;

    // The reader's choice wins over the palette slot. Two runs of the same variable, or a variable
    // someone thinks of as red, are worth more than a consistent rotation nobody chose.
    const colourFor = (key: string) => colours[key] ?? seriesColorValue(key);

    // A band is drawn as two stacked area series: an invisible one up to the minimum, then a
    // translucent one spanning the range. ECharts has no first-class band, and this is the standard
    // way of getting one without a second chart library.
    const bandSeries = (bands ?? []).flatMap((band) => {
      const colour = colourFor(band.base);
      const spread = band.max.map(([tick, high], index) => [tick, high - (band.min[index]?.[1] ?? 0)]);
      return [
        {
          name: `${band.base} floor`,
          type: "line",
          stack: band.key,
          data: band.min,
          showSymbol: false,
          lineStyle: { opacity: 0 },
          areaStyle: { opacity: 0 },
          silent: true,
          // Kept out of the legend and tooltip: it is scaffolding for the band above it, not a
          // series anybody asked to see.
          tooltip: { show: false },
          legendHoverLink: false,
          z: 1,
        },
        {
          name: `${band.base} spread`,
          type: "line",
          stack: band.key,
          data: spread,
          showSymbol: false,
          lineStyle: { opacity: 0 },
          areaStyle: { color: colour, opacity: 0.16 },
          silent: true,
          tooltip: { show: false },
          legendHoverLink: false,
          z: 1,
        },
        {
          name: band.base,
          type: "line",
          data: band.mean,
          showSymbol: false,
          symbolSize: 8,
          lineStyle: { width: 2, color: colour },
          itemStyle: { color: colour },
          emphasis: { focus: "none", scale: 1.6, lineStyle: { width: 2.5 } },
          blur: { lineStyle: { opacity: 1 }, itemStyle: { opacity: 1 } },
          z: 3,
        },
      ];
    });

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
          const raw = params as { axisValue: number; seriesName: string; data: [number, number]; color: string }[];
          // Scaffolding series for the bands carry no meaning on their own; the mean line beside
          // them says everything the reader wants at this tick.
          const points = raw
            .filter((point) => !/ (floor|spread)$/.test(point.seriesName))
            // Sorted by value so the lines read in the order they appear on the chart, rather than
            // in whatever order the series happen to be declared.
            .sort((left, right) => (right.data?.[1] ?? 0) - (left.data?.[1] ?? 0));
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
                   <span style="font-variant-numeric:tabular-nums">${formatValue(point.data[1], format)}</span>
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
        // Named so a transformed chart cannot be read as a raw one: the axis says what it measures.
        name: axisLabel,
        nameLocation: "end",
        nameGap: 12,
        nameTextStyle: { color: textMuted, fontSize: 10, align: "left" },
        min: yMin ?? "dataMin",
        max: yMax ?? "dataMax",
        axisLine: { show: false },
        axisTick: { show: false },
        // Formatted, or the axis prints its endpoints at full float precision: pinning the extremes
        // to dataMin/dataMax makes them real data values rather than round numbers, and an axis
        // labelled 0.9348106517081437 is not a label, it is a leak.
        axisLabel: {
          color: textMuted,
          fontSize: 11,
          formatter: (value: number) => formatValue(value, format),
        },
        // Recessive grid: present enough to read a value off, quiet enough to stay behind the data.
        splitLine: { lineStyle: { color: grid, width: 1 } },
      },
      // Drag across the plot to select a tick window. The toolbox carries the tool and its icon
      // can switch it off again; the cursor is armed from code below so the gesture works without
      // finding the icon first.
      toolbox: {
        right: 8,
        top: 0,
        itemSize: 12,
        iconStyle: { borderColor: textMuted },
        emphasis: { iconStyle: { borderColor: textSecondary } },
        feature: {
          dataZoom: {
            yAxisIndex: "none",
            title: { zoom: "Drag across the chart to select a tick range", back: "Undo the zoom" },
          },
        },
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
      series: [...bandSeries, ...series.map((entry) => ({
        name: entry.key,
        type: "line",
        data: entry.points,
        showSymbol: false,
        symbolSize: 8,
        // Thin marks; the data is the ink.
        lineStyle: { width: 2, color: colourFor(entry.key) },
        itemStyle: { color: colourFor(entry.key) },
        // Hovering marks the point under the cursor on every line; it does not hide the lines.
        // `focus: "series"` dimmed everything except the one series being pointed at, which meant
        // moving the mouse over a chart of eight variables erased seven of them - exactly when the
        // reader is trying to compare them.
        emphasis: {
          focus: "none",
          scale: 1.6,
          lineStyle: { width: 2.5 },
          itemStyle: { borderColor: surface, borderWidth: 2 },
        },
        blur: { lineStyle: { opacity: 1 }, itemStyle: { opacity: 1 } },
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
        markArea: undefined,
        markLine: markers.length || events.length
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
              data: [
                // The reader's own cursor, and the run's actual events. Both are vertical rules,
                // but the events come from the log rather than from a click, which is the whole
                // point: "something happened here" is a fact about the run, not a bookmark.
                ...markers.map((marker) => ({ xAxis: marker.tick, name: marker.label })),
                // Unlabelled on purpose. Labelling each one turns a run with a checkpoint every
                // 250 ticks into a wall of rotated text with the data behind it; the rule says
                // "something happened here", and the log panel beside the chart says what.
                ...events.map((event) => ({
                  xAxis: event.tick,
                  label: { show: false },
                  lineStyle: {
                    color:
                      event.type in EVENT_SLOT
                        ? seriesColorValue(event.type, EVENT_SLOT[event.type])
                        : axis,
                    type: "dotted",
                    width: 1,
                    opacity: 0.5,
                  },
                })),
              ],
            }
          : undefined,
      }))],
    } as EChartsOption;
  }, [series, bands, events, axisLabel, colours, format, theme, markers, range, yMin, yMax]);

  const { ref, instance } = useECharts(option, [option], {
    onClick: (params) => {
      const value = params.value as [number, number] | undefined;
      if (value && onTickClick) onTickClick(value[0]);
    },
  });



  /**
   * Zooming one panel adjusts the shared range, which the other panels then follow.
   *
   * This has to be an effect, not a memo. A memo body runs *during* render, when the chart has not
   * been created yet and `instance.current` is still null; it took the early return, never
   * subscribed, and never ran again because its dependencies never changed. The shared range was
   * therefore dead: zooming a panel moved nothing else, and the "Reset zoom" control it feeds could
   * not appear at all.
   */
  // The full extent of what is plotted, used to tell "zoomed in" from "zoomed all the way out".
  const [dataFrom, dataTo] = useMemo(() => {
    const ticks = [
      ...series.flatMap((entry) => entry.points.map(([tick]) => tick)),
      ...bands.flatMap((band) => band.mean.map(([tick]) => tick)),
    ];
    return ticks.length ? [Math.min(...ticks), Math.max(...ticks)] : [0, 0];
  }, [series, bands]);

  useEffect(() => {
    const chart = instance.current;
    if (!chart || !option) return;

    // Arms drag-to-select, so a range can be chosen with the gesture people already try rather
    // than only through the toolbox icon. Re-armed after every option application, since the
    // `notMerge` setOption that useECharts performs resets the global cursor.
    chart.dispatchAction({
      type: "takeGlobalCursor",
      key: "dataZoomSelect",
      dataZoomSelectActive: true,
    });

    if (!onRangeChange) return;
    const onZoom = () => {
      // Read from the dataZoom model, not from the axis. Zooming does not rewrite `xAxis.min`: it
      // stays the configured "dataMin"/"dataMax" string while the window lives here, so the older
      // check for a numeric axis bound never once passed and no zoom ever reached the page.
      const zooms = (chart.getOption() as {
        dataZoom?: { startValue?: number; endValue?: number }[];
      }).dataZoom;
      const window = zooms?.find(
        (zoom) => typeof zoom.startValue === "number" && typeof zoom.endValue === "number",
      );
      if (!window) return;

      const from = window.startValue as number;
      const to = window.endValue as number;
      // A window covering everything is not a selection. Reporting it as one would leave the page
      // offering to filter the log to a range that excludes nothing.
      onRangeChange(from <= dataFrom && to >= dataTo ? null : { from, to });
    };
    chart.on("datazoom", onZoom);
    return () => {
      chart.off("datazoom", onZoom);
    };
  }, [instance, option, onRangeChange, dataFrom, dataTo]);

  if (!series.length && !bands.length) {
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
