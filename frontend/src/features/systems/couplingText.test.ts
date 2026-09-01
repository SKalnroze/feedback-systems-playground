import { couplingIsInvalid, explainCoupling, MAX_PAIRS } from "@/features/systems/couplingText";
import { describe, expect, it } from "vitest";

const mean = { aggregate: "MEAN" } as const;

describe("explaining a coupling", () => {
  it("names the real member counts rather than talking in the abstract", () => {
    const text = explainCoupling(
      { mode: "MANY_TO_MANY_ALL", ...mean },
      500,
      400,
      "staff",
      "customers",
    );
    expect(text).toContain("500");
    expect(text).toContain("400");
    expect(text).toContain("200,000");
  });

  it("says what is wrong, and what to do, when pairing is impossible", () => {
    const text = explainCoupling({ mode: "ONE_TO_ONE", ...mean }, 8, 5, "staff", "customers");
    expect(text).toContain("8");
    expect(text).toContain("5");
    expect(text).toMatch(/equal|reduce/i);
  });

  it("recognises a group linked to itself as a loop inside each member", () => {
    const text = explainCoupling({ mode: "ONE_TO_ONE", ...mean }, 300, 300, "crowd", "crowd");
    expect(text).toMatch(/itself/);
  });

  it("warns that one-to-many ignores the rest of a group source", () => {
    const text = explainCoupling({ mode: "ONE_TO_MANY", ...mean }, 40, 10, "staff", "customers");
    expect(text).toMatch(/only the first member/i);
  });

  it("describes what AUTO will actually do for these endpoints", () => {
    expect(explainCoupling({ mode: "AUTO", ...mean }, 1, 1, "a", "b")).toMatch(/plain link/i);
    expect(explainCoupling({ mode: "AUTO", ...mean }, 1, 50, "a", "b")).toMatch(/all 50/);
    expect(explainCoupling({ mode: "AUTO", ...mean }, 50, 1, "a", "b")).toMatch(/mean/);
  });
});

describe("catching a coupling the engine would reject", () => {
  it("rejects uneven one-to-one", () => {
    expect(couplingIsInvalid({ mode: "ONE_TO_ONE", ...mean }, 8, 5)).toBe(true);
    expect(couplingIsInvalid({ mode: "ONE_TO_ONE", ...mean }, 8, 8)).toBe(false);
  });

  it("rejects an every-pair coupling above the pair budget", () => {
    expect(couplingIsInvalid({ mode: "MANY_TO_MANY_ALL", ...mean }, 600, 600)).toBe(true);
    // Exactly at the budget is allowed; the engine's check is strictly greater than.
    expect(couplingIsInvalid({ mode: "MANY_TO_MANY_ALL", ...mean }, 500, MAX_PAIRS / 500)).toBe(false);
  });

  it("allows the reducing couplings at any size", () => {
    expect(couplingIsInvalid({ mode: "MANY_TO_MANY_AGGREGATE", ...mean }, 2000, 2000)).toBe(false);
    expect(couplingIsInvalid({ mode: "MANY_TO_MANY_RANDOM", ...mean }, 2000, 2000)).toBe(false);
  });
});
