import { DEFAULT_FORMAT, formatValue } from "@/lib/valueFormat";
import { describe, expect, it } from "vitest";

describe("formatting a value", () => {
  it("uses the requested number of decimals", () => {
    expect(formatValue(0.123456, { decimals: 2, percent: false })).toBe("0.12");
    expect(formatValue(0.123456, { decimals: 4, percent: false })).toBe("0.1235");
  });

  it("reads a unit-scale value as a percentage when asked", () => {
    expect(formatValue(0.42, { decimals: 2, percent: true })).toBe("42%");
    expect(formatValue(1, { decimals: 2, percent: true })).toBe("100%");
    expect(formatValue(-0.5, { decimals: 2, percent: true })).toBe("-50%");
  });

  it("leaves values off the unit scale alone in percent mode", () => {
    // A memory count of 120 is not 12,000%. A formatter that has to be watched is worse than none.
    expect(formatValue(120, { decimals: 2, percent: true })).toBe("120.00");
    expect(formatValue(3.5, { decimals: 1, percent: true })).toBe("3.5");
  });

  it("says so plainly when there is no number", () => {
    expect(formatValue(Number.NaN, DEFAULT_FORMAT)).toBe("—");
    expect(formatValue(Number.POSITIVE_INFINITY, DEFAULT_FORMAT)).toBe("—");
  });
});
