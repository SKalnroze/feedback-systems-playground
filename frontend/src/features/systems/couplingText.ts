import type { Coupling, CouplingMode } from "@/api/types";

export const COUPLING_MODES: CouplingMode[] = [
  "AUTO",
  "ONE_TO_ONE",
  "ONE_TO_MANY",
  "MANY_TO_ONE",
  "MANY_TO_MANY_AGGREGATE",
  "MANY_TO_MANY_RANDOM",
  "MANY_TO_MANY_ALL",
];

export const COUPLING_LABEL: Record<CouplingMode, string> = {
  AUTO: "work it out from the endpoints",
  ONE_TO_ONE: "one to one",
  ONE_TO_MANY: "one to many",
  MANY_TO_ONE: "many to one",
  MANY_TO_MANY_AGGREGATE: "many to many, through an average",
  MANY_TO_MANY_RANDOM: "many to many, randomly paired",
  MANY_TO_MANY_ALL: "many to many, every pair",
};

/** Above this many pairs the engine refuses an every-pair coupling. */
export const MAX_PAIRS = 250_000;

function plural(count: number, noun: string): string {
  return `${count.toLocaleString()} ${noun}${count === 1 ? "" : "s"}`;
}

/**
 * What a coupling means for the endpoints this link actually has.
 *
 * A generic gloss ("each source affects each target") is no help at the moment of choosing, because
 * the reader cannot tell what it will cost or whether it is even legal here. Naming the real counts
 * turns the choice into an arithmetic one.
 */
export function explainCoupling(
  coupling: Coupling,
  sourceMembers: number,
  targetMembers: number,
  sourceLabel: string,
  targetLabel: string,
): string {
  const source = Math.max(1, sourceMembers);
  const target = Math.max(1, targetMembers);
  const aggregate = coupling.aggregate.toLowerCase();

  switch (coupling.mode) {
    case "AUTO":
      if (source <= 1 && target <= 1) return `A plain link from ${sourceLabel} to ${targetLabel}.`;
      if (source <= 1) return `${sourceLabel} reaches all ${plural(target, "member")} of ${targetLabel}.`;
      if (target <= 1)
        return `The ${aggregate} of ${plural(source, "member")} of ${sourceLabel} reaches ${targetLabel}.`;
      if (source === target)
        return `Same-sized groups, so each of the ${source.toLocaleString()} members pairs with its counterpart.`;
      return `Different sizes, so ${sourceLabel} is reduced to its ${aggregate} and that reaches every member of ${targetLabel}.`;

    case "ONE_TO_ONE":
      if (source !== target)
        return `Cannot pair members: ${sourceLabel} has ${source.toLocaleString()} and ${targetLabel} has ${target.toLocaleString()}. Make them equal, or pick a coupling that reduces.`;
      if (sourceLabel === targetLabel)
        return `Each of the ${source.toLocaleString()} members feeds itself — a loop inside every member, none between them.`;
      return `Member 1 of ${sourceLabel} feeds member 1 of ${targetLabel}, and so on for all ${source.toLocaleString()}.`;

    case "ONE_TO_MANY":
      if (source > 1)
        return `Only the first member of ${sourceLabel} is read, and it reaches all ${plural(target, "member")} of ${targetLabel}. Use an aggregate coupling to involve the rest.`;
      return `${sourceLabel} reaches all ${plural(target, "member")} of ${targetLabel}, each getting the same value.`;

    case "MANY_TO_ONE":
      return `The ${aggregate} across ${plural(source, "member")} of ${sourceLabel} becomes one number, which reaches ${targetLabel}.`;

    case "MANY_TO_MANY_AGGREGATE":
      return `${sourceLabel} is reduced to its ${aggregate}, and that one number reaches every one of ${plural(target, "member")} in ${targetLabel}.`;

    case "MANY_TO_MANY_RANDOM":
      return `Each of ${plural(target, "member")} in ${targetLabel} is influenced by one randomly chosen member of ${sourceLabel}, redrawn every tick. Seeded, so a replay pairs them identically.`;

    case "MANY_TO_MANY_ALL": {
      const pairs = source * target;
      if (pairs > MAX_PAIRS)
        return `${source.toLocaleString()} × ${target.toLocaleString()} = ${pairs.toLocaleString()} pairs every tick, above the limit of ${MAX_PAIRS.toLocaleString()}. Reduce a group, or couple through an average.`;
      return `Every one of ${plural(source, "member")} in ${sourceLabel} reaches every one of ${plural(target, "member")} in ${targetLabel}: ${pairs.toLocaleString()} pairs each tick.`;
    }

    default:
      return "";
  }
}

/** True when this choice would be rejected by the engine's validator. */
export function couplingIsInvalid(
  coupling: Coupling,
  sourceMembers: number,
  targetMembers: number,
): boolean {
  const source = Math.max(1, sourceMembers);
  const target = Math.max(1, targetMembers);
  if (coupling.mode === "ONE_TO_ONE") return source !== target;
  if (coupling.mode === "MANY_TO_MANY_ALL") return source * target > MAX_PAIRS;
  return false;
}
