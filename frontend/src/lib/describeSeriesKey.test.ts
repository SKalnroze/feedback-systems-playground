import { describeSeriesKey } from "@/lib/utils";
import { describe, expect, it } from "vitest";

const groups = new Map([
  ["staff", { label: "staff", count: 500 }],
  ["supervisor", { label: "supervisor", count: 1 }],
]);

describe("saying a series key in words", () => {
  it("turns a group statistic into a sentence", () => {
    expect(describeSeriesKey("staff.morale.mean", groups)).toBe("average morale across 500 staff");
    expect(describeSeriesKey("staff.morale.min", groups)).toBe("lowest morale among 500 staff");
    expect(describeSeriesKey("staff.morale.max", groups)).toBe("highest morale among 500 staff");
  });

  it("splits camel case into readable words", () => {
    expect(describeSeriesKey("staff.memoryStrength.mean", groups)).toBe(
      "average memory strength across 500 staff",
    );
  });

  it("leaves a single object alone rather than claiming an average of one", () => {
    // "average morale across 1 supervisor" is technically true and reads as nonsense.
    expect(describeSeriesKey("supervisor.morale", groups)).toBe("supervisor · morale");
  });

  it("falls back sensibly for a key it knows nothing about", () => {
    expect(describeSeriesKey("global.workload", groups)).toBe("workload");
    expect(describeSeriesKey("mystery", groups)).toBe("mystery");
  });
});
