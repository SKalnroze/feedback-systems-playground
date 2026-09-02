import { useCallback, useEffect, useState } from "react";

/** How numbers are written throughout the application. */
export type ValueFormat = {
  decimals: number;
  /** Show unit-scale values as percentages, which is how most people read a 0–1 stock. */
  percent: boolean;
};

export const DEFAULT_FORMAT: ValueFormat = { decimals: 2, percent: false };

const STORAGE_KEY = "fsp.valueFormat";

/**
 * Formats a value for display.
 *
 * Percent mode is deliberately conservative: it only converts values that plausibly live on a unit
 * scale. Rendering a workload of 0.4 as "40%" is helpful; rendering a memory count of 120 as
 * "12000%" is not, and a formatter that has to be watched is worse than none.
 */
export function formatValue(value: number, format: ValueFormat = DEFAULT_FORMAT): string {
  if (!Number.isFinite(value)) return "—";
  if (format.percent && value >= -1.0000001 && value <= 1.0000001) {
    return `${(value * 100).toFixed(Math.max(0, format.decimals - 2))}%`;
  }
  return value.toFixed(format.decimals);
}

/**
 * The reader's number-formatting preference, remembered.
 *
 * Two decimals suits a unit stock and hides the movement in a variable that lives in the third;
 * five suits the latter and turns a table of stocks into noise. Which one is right depends on what
 * is being looked at, so it is a choice rather than a constant.
 */
export function useValueFormat() {
  const [format, setFormat] = useState<ValueFormat>(DEFAULT_FORMAT);

  useEffect(() => {
    try {
      const stored = window.localStorage.getItem(STORAGE_KEY);
      if (stored) setFormat({ ...DEFAULT_FORMAT, ...(JSON.parse(stored) as ValueFormat) });
    } catch {
      // A malformed preference is not worth failing the page over.
    }
  }, []);

  const update = useCallback((change: Partial<ValueFormat>) => {
    setFormat((current) => {
      const next = { ...current, ...change };
      try {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
      } catch {
        // Storage may be unavailable; the preference simply will not persist.
      }
      return next;
    });
  }, []);

  return { format, update };
}
