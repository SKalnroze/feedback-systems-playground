import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** Conditional class names, with later Tailwind utilities winning over earlier ones. */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}

/** The categorical slots, in the fixed order they must be assigned in. */
export const SERIES_COLORS = [
  "var(--series-1)",
  "var(--series-2)",
  "var(--series-3)",
  "var(--series-4)",
  "var(--series-5)",
  "var(--series-6)",
  "var(--series-7)",
  "var(--series-8)",
] as const;

/**
 * Colour for a series, keyed by its identity rather than its position in the current selection.
 *
 * Hashing the key means removing one series from a chart never repaints the others, which is what
 * makes two charts of the same run comparable at a glance.
 */
export function seriesColor(key: string, explicitIndex?: number): string {
  if (explicitIndex !== undefined) {
    return SERIES_COLORS[explicitIndex % SERIES_COLORS.length] as string;
  }
  let hash = 0;
  for (let i = 0; i < key.length; i++) {
    hash = (hash * 31 + key.charCodeAt(i)) | 0;
  }
  return SERIES_COLORS[Math.abs(hash) % SERIES_COLORS.length] as string;
}

/**
 * The same colour, resolved to a value a charting library can actually use.
 *
 * {@link seriesColor} returns a `var(--series-n)` token, which is right for the DOM and useless to
 * a canvas renderer: ECharts cannot resolve custom properties, so it silently discards the string
 * and falls back to its default grey. Every line on every chart came out the same colour because
 * of it.
 */
export function seriesColorValue(key: string, explicitIndex?: number): string {
  const token = seriesColor(key, explicitIndex);
  const name = token.startsWith("var(") ? token.slice(4, -1).trim() : token;
  return name.startsWith("--") ? cssVar(name, "#8899a6") : name;
}

/** Reads a CSS custom property, for the charting library which cannot use `var()` directly. */
export function cssVar(name: string, fallback = "#888"): string {
  if (typeof window === "undefined") return fallback;
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
}

export function formatNumber(value: number, digits = 3): string {
  if (!Number.isFinite(value)) return "-";
  if (Math.abs(value) >= 1000) return value.toFixed(0);
  return value.toFixed(digits).replace(/\.?0+$/, "") || "0";
}

export function formatTick(tick: number): string {
  return tick.toLocaleString("en-GB");
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} kB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function formatRelativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  const seconds = Math.round((Date.now() - then) / 1000);
  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`;
  if (seconds < 86_400) return `${Math.round(seconds / 3600)} h ago`;
  return `${Math.round(seconds / 86_400)} d ago`;
}

/** Human label for a series key such as `ana.trust` or `global.workload`. */
export function seriesLabel(key: string): string {
  const [head, ...rest] = key.split(".");
  if (!rest.length) return key;
  const tail = rest.join(".");
  if (head === "global") return tail;
  if (head === "system") return tail.replace(/([A-Z])/g, " $1").toLowerCase();
  return `${head} · ${tail.replace(/([A-Z])/g, " $1").toLowerCase()}`;
}
