import { formatBytes, formatNumber, formatTick, seriesColor, seriesLabel, SERIES_COLORS } from "@/lib/utils";
import { describe, expect, it } from "vitest";

describe("series colours", () => {
  it("gives a series the same colour every time", () => {
    expect(seriesColor("ana.trust")).toBe(seriesColor("ana.trust"));
  });

  it("does not repaint the survivors when a series is removed", () => {
    // Colour follows the identity of the series, not its position in the current selection: a
    // filter that changes what is charted must not change what the remaining lines look like.
    const before = ["ana.trust", "ben.trust", "chi.trust"].map((key) => seriesColor(key));
    const after = ["ana.trust", "chi.trust"].map((key) => seriesColor(key));

    expect(after[0]).toBe(before[0]);
    expect(after[1]).toBe(before[2]);
  });

  it("only ever uses the defined slots", () => {
    for (const key of ["a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"]) {
      expect(SERIES_COLORS).toContain(seriesColor(key));
    }
  });

  it("honours an explicit slot when one is given", () => {
    expect(seriesColor("anything", 0)).toBe(SERIES_COLORS[0]);
    expect(seriesColor("anything", 3)).toBe(SERIES_COLORS[3]);
  });
});

describe("labels", () => {
  it("reads an object series as owner and variable", () => {
    expect(seriesLabel("ana.trust")).toBe("ana · trust");
  });

  it("drops the prefix from a global series", () => {
    expect(seriesLabel("global.workload")).toBe("workload");
  });

  it("spaces out a camel-case measure", () => {
    expect(seriesLabel("ana.memoryStrength")).toBe("ana · memory strength");
    expect(seriesLabel("system.memoryCount")).toBe("memory count");
  });

  it("leaves an unqualified key alone", () => {
    expect(seriesLabel("tick")).toBe("tick");
  });
});

describe("formatting", () => {
  it("trims trailing zeros from small numbers", () => {
    expect(formatNumber(0.5)).toBe("0.5");
    expect(formatNumber(0.123456)).toBe("0.123");
    expect(formatNumber(0)).toBe("0");
  });

  it("drops the decimals from large numbers", () => {
    expect(formatNumber(1234.567)).toBe("1235");
  });

  it("shows a dash rather than NaN", () => {
    expect(formatNumber(Number.NaN)).toBe("-");
    expect(formatNumber(Number.POSITIVE_INFINITY)).toBe("-");
  });

  it("groups ticks for legibility", () => {
    expect(formatTick(1234567)).toBe("1,234,567");
  });

  it("scales byte sizes", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(2048)).toBe("2.0 kB");
    expect(formatBytes(5 * 1024 * 1024)).toBe("5.0 MB");
  });
});
