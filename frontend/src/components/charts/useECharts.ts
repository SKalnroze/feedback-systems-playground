import * as echarts from "echarts/core";
import { BarChart, LineChart, HeatmapChart } from "echarts/charts";
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  ToolboxComponent,
  TooltipComponent,
  VisualMapComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import { useEffect, useRef } from "react";

// Registered explicitly rather than importing the whole library: the full bundle is several times
// the size of everything else in this application put together.
echarts.use([
  LineChart,
  // Bar is needed for the distribution histogram. A series whose type is not registered draws
  // absolutely nothing and reports no error, which looks exactly like a data problem.
  BarChart,
  HeatmapChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  MarkLineComponent,
  // The toolbox carries the drag-a-range-to-zoom tool. Like BarChart above, an unregistered
  // component is simply absent from the rendered chart rather than being an error.
  ToolboxComponent,
  VisualMapComponent,
  CanvasRenderer,
]);

export type EChartsOption = Parameters<echarts.ECharts["setOption"]>[0];

/**
 * Binds an ECharts instance to a container element.
 *
 * The option is applied with `notMerge` so that removing a series actually removes it - merging
 * would leave the old one on the canvas, which is exactly the bug that makes a series picker feel
 * broken. Resizing is observed rather than hooked to the window, since panels change size when the
 * layout does, not only when the window does.
 */
export function useECharts(
  option: EChartsOption | null,
  deps: unknown[],
  handlers?: { onClick?: (params: { dataIndex: number; value: unknown; seriesName?: string }) => void },
): { ref: React.RefObject<HTMLDivElement | null>; instance: React.RefObject<echarts.ECharts | null> } {
  const ref = useRef<HTMLDivElement | null>(null);
  const instance = useRef<echarts.ECharts | null>(null);
  const clickHandler = useRef(handlers?.onClick);
  clickHandler.current = handlers?.onClick;

  useEffect(() => {
    if (!ref.current) return undefined;
    const chart = echarts.init(ref.current, undefined, { renderer: "canvas" });
    instance.current = chart;

    chart.on("click", (params) => {
      clickHandler.current?.({
        dataIndex: params.dataIndex,
        value: params.value,
        seriesName: params.seriesName,
      });
    });

    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(ref.current);

    return () => {
      observer.disconnect();
      chart.dispose();
      instance.current = null;
    };
  }, []);

  useEffect(() => {
    if (!instance.current || !option) return;
    instance.current.setOption(option, { notMerge: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { ref, instance };
}

export { echarts };
