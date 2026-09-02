import { cn } from "@/lib/utils";
import { useMemo } from "react";

export type SparklinePoint = [number, number];

/**
 * A thumbnail of one series, drawn as plain SVG.
 *
 * Deliberately not an ECharts chart. These appear dozens at a time — one per row of a list, one per
 * expanded memory — and each ECharts instance carries a canvas, a resize observer and a teardown.
 * A path element costs none of that, and at this size the only thing a reader can take from the
 * picture is its shape, which is exactly what a path gives.
 *
 * It has no axes on purpose: a shape this small cannot be read for values, and drawing ticks on it
 * would invite exactly the misreading it cannot support. The numbers belong beside it.
 */
export function Sparkline({
  points,
  width = 96,
  height = 24,
  colour = "var(--accent)",
  /** A horizontal rule, e.g. a retrieval threshold the curve eventually crosses. */
  threshold,
  /** Vertical rules at given x values, e.g. where a memory is now. */
  marks = [],
  /** Fixes the vertical scale. Without it the curve is scaled to its own extremes. */
  yMin,
  yMax,
  className,
  label,
}: {
  points: SparklinePoint[];
  width?: number;
  height?: number;
  colour?: string;
  threshold?: number;
  marks?: number[];
  yMin?: number;
  yMax?: number;
  className?: string;
  label?: string;
}) {
  const geometry = useMemo(() => {
    if (points.length < 2) return null;

    const xs = points.map(([x]) => x);
    const ys = points.map(([, y]) => y);
    const x0 = Math.min(...xs);
    const x1 = Math.max(...xs);
    const low = yMin ?? Math.min(...ys);
    const high = yMax ?? Math.max(...ys);

    // A flat series has no range to scale against. Drawing it down the middle is the honest
    // picture: nothing moved. Scaling it to fill the box would turn floating-point dust into a
    // dramatic shape, which is the classic way a sparkline lies.
    const spanX = x1 - x0 || 1;
    const spanY = high - low || 1;
    const flat = high - low < 1e-12;

    const at = (point: SparklinePoint): [number, number] => [
      ((point[0] - x0) / spanX) * width,
      flat ? height / 2 : height - ((point[1] - low) / spanY) * height,
    ];

    const path = points
      .map((point, index) => {
        const [x, y] = at(point);
        return `${index === 0 ? "M" : "L"}${x.toFixed(1)},${y.toFixed(1)}`;
      })
      .join(" ");

    const yFor = (value: number) => height - ((value - low) / spanY) * height;
    const xFor = (value: number) => ((value - x0) / spanX) * width;

    return {
      path,
      thresholdY: threshold !== undefined && !flat ? yFor(threshold) : null,
      markXs: marks.map(xFor).filter((x) => x >= 0 && x <= width),
      last: at(points[points.length - 1]!),
    };
  }, [points, width, height, threshold, marks, yMin, yMax]);

  if (!geometry) {
    return (
      <span
        className={cn("inline-block text-[10px] text-[var(--text-muted)]", className)}
        style={{ width, height }}
      >
        —
      </span>
    );
  }

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className={cn("overflow-visible", className)}
      role="img"
      aria-label={label ?? "Sparkline"}
    >
      {geometry.thresholdY !== null && geometry.thresholdY >= 0 && geometry.thresholdY <= height ? (
        <line
          x1={0}
          x2={width}
          y1={geometry.thresholdY}
          y2={geometry.thresholdY}
          stroke="var(--text-muted)"
          strokeWidth={1}
          strokeDasharray="3 3"
          opacity={0.7}
        />
      ) : null}
      {geometry.markXs.map((x, index) => (
        <line
          key={index}
          x1={x}
          x2={x}
          y1={0}
          y2={height}
          stroke="var(--text-muted)"
          strokeWidth={1}
          opacity={0.5}
        />
      ))}
      <path d={geometry.path} fill="none" stroke={colour} strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
      <circle cx={geometry.last[0]} cy={geometry.last[1]} r={1.8} fill={colour} />
    </svg>
  );
}
