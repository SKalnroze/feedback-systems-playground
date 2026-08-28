/**
 * The shapes the backend actually sends.
 *
 * Hand-written rather than generated: the API is small and stable, and a generated client would
 * bury the polymorphic spec types under machinery without making them any easier to work with.
 * The `kind` discriminators here are the same strings the engine reports, so a `switch` on `kind`
 * is exhaustive in both languages.
 */

// --- spec ---------------------------------------------------------------------------------------

export type DecayCommon = { floor: number; interference: number };

export type DecayModel =
  | { kind: "none"; common: DecayCommon }
  | { kind: "exponential"; halfLife: number; common: DecayCommon }
  | { kind: "power-law"; exponent: number; common: DecayCommon }
  | { kind: "ebbinghaus"; baseStability: number; stabilityGrowth: number; common: DecayCommon }
  | { kind: "linear"; ratePerTick: number; common: DecayCommon }
  | { kind: "logistic"; steepness: number; midpoint: number; common: DecayCommon }
  | { kind: "step-threshold"; steps: { afterTicks: number; retention: number }[]; common: DecayCommon }
  | {
      kind: "act-r-base-level";
      decayExponent: number;
      threshold: number;
      noiseScale: number;
      common: DecayCommon;
    };

export type DecayKind = DecayModel["kind"];

export type Transfer =
  | { kind: "transfer-linear" }
  | { kind: "sigmoid"; steepness: number; midpoint: number; amplitude: number }
  | { kind: "tanh"; scale: number; amplitude: number }
  | { kind: "threshold"; threshold: number; below: number; above: number }
  | { kind: "saturating"; min: number; max: number }
  | { kind: "logarithmic"; scale: number }
  | { kind: "power"; exponent: number };

export type EventGenerator =
  | { kind: "never" }
  | { kind: "fixed-schedule"; ticks: number[] }
  | { kind: "bernoulli"; probability: number }
  | { kind: "poisson"; ratePerTick: number }
  | { kind: "periodic"; interval: number; jitter: number; offset: number }
  | {
      kind: "markov-chain";
      states: {
        id: string;
        occurrenceProbability: number;
        transitions: { toState: string; probability: number }[];
      }[];
    }
  | { kind: "burst"; baseRate: number; excitation: number; decay: number };

export type GeneratorKind = EventGenerator["kind"];

export type TargetSelector =
  | { kind: "global-target" }
  | { kind: "everyone"; typeId: string | null }
  | { kind: "random-k"; typeId: string | null; count: number }
  | { kind: "matching"; typeId: string | null; condition: unknown }
  | { kind: "specific"; objectIds: string[] }
  | { kind: "random-pair"; typeId: string | null; pairs: number }
  | { kind: "named-pair"; primaryId: string; secondaryId: string }
  | { kind: "all-pairs"; typeId: string | null };

export type Scope = "SELF" | "TARGET" | "GLOBAL";

/** Numeric expressions and predicates are edited as trees; the UI only needs to pass them through. */
export type NumExpr = { kind: string } & Record<string, unknown>;
export type Predicate = { kind: string } & Record<string, unknown>;

export type Effect = { kind: string } & Record<string, unknown>;

export type VariableRef =
  | { kind: "global"; name: string }
  | { kind: "object"; objectId: string; name: string }
  | { kind: "type-aggregate"; typeId: string; name: string; aggregate: string };

export type VariableKind = "STOCK" | "AUXILIARY" | "CONSTANT";

export type VariableSpec = {
  name: string;
  label: string;
  kind: VariableKind;
  initial: number;
  min: number;
  max: number;
};

export type MemorySettings = {
  defaultDecay: DecayModel;
  retrievalThreshold: number;
  capacity: number;
  pruneForgotten: boolean;
  similarityThreshold: number;
};

export type ObjectTypeSpec = {
  id: string;
  label: string;
  variables: VariableSpec[];
  memory: MemorySettings;
  defaultTags: string[];
  defaultFeatures: Record<string, number>;
};

export type ObjectSpec = {
  id: string;
  typeId: string;
  label: string;
  variables: Record<string, number>;
  tags: string[];
  features: Record<string, number>;
};

export type LinkSpec = {
  id: string;
  label: string;
  source: VariableRef;
  target: VariableRef;
  gain: number;
  delayTicks: number;
  transfer: Transfer;
  usesRate: boolean;
};

export type EventSpec = {
  id: string;
  label: string;
  description: string;
  generator: EventGenerator;
  selector: TargetSelector;
  condition: Predicate;
  effects: Effect[];
  cooldownTicks: number;
  maxOccurrences: number;
};

export type TriggerCondition = { kind: string } & Record<string, unknown>;

export type MemoryTrigger = {
  id: string;
  label: string;
  filter: { kinds: string[]; minStrength: number; maxStrength: number; minAge: number };
  condition: TriggerCondition;
  effect: {
    strengthBoost: number;
    stabilityGain: number;
    resetClock: boolean;
    valenceShift: number;
    spawnDerived: string | null;
    derivedStrength: number;
    emitEventId: string | null;
  };
  maxPerTick: number;
};

export type InteractionConfig = {
  ruleId: string;
  enabled: boolean;
  weight: number;
  params: Record<string, number>;
};

export type SimulationSettings = {
  metricSampleInterval: number;
  autoCheckpointInterval: number;
  maxTicks: number;
  interactionsPerTick: number;
  sampleMemoryStrength: boolean;
};

export type SystemSpec = {
  id: string;
  name: string;
  description: string;
  moduleIds: string[];
  objectTypes: ObjectTypeSpec[];
  objects: ObjectSpec[];
  globalVariables: VariableSpec[];
  links: LinkSpec[];
  events: EventSpec[];
  triggers: MemoryTrigger[];
  interactions: InteractionConfig[];
  settings: SimulationSettings;
};

// --- systems ------------------------------------------------------------------------------------

export type SystemDefinition = {
  id: string;
  name: string;
  description: string;
  draft: SystemSpec | null;
  createdAt: string;
  updatedAt: string;
};

export type SystemVersion = {
  id: string;
  systemId: string;
  version: number;
  spec: SystemSpec;
  checksum: string;
  publishedAt: string;
};

export type ValidationIssue = {
  severity: "ERROR" | "WARNING";
  elementKind: string;
  elementId: string;
  message: string;
};

export type FeedbackLoop = {
  linkIds: string[];
  polarity: "REINFORCING" | "BALANCING";
  totalDelay: number;
};

export type ValidationReport = {
  valid: boolean;
  issues: ValidationIssue[];
  loops: FeedbackLoop[];
  missingRules: string[];
};

// --- modules ------------------------------------------------------------------------------------

export type RuleParameter = {
  name: string;
  label: string;
  fallback: number;
  min: number;
  max: number;
};

export type RuleView = {
  id: string;
  label: string;
  description: string;
  parameters: RuleParameter[];
};

export type PresetView = { id: string; label: string; description: string; moduleId: string };

export type ModuleView = {
  id: string;
  label: string;
  description: string;
  objectTypes: ObjectTypeSpec[];
  rules: RuleView[];
  presets: PresetView[];
};

export type PaletteView = {
  kinds: Record<string, string[]>;
  objectTypes: ObjectTypeSpec[];
  rules: RuleView[];
};

// --- runs ---------------------------------------------------------------------------------------

export type RunStatus = "CREATED" | "RUNNING" | "PAUSED" | "STOPPED" | "COMPLETED" | "FAILED";

export type RunSummary = {
  id: string;
  name: string;
  status: RunStatus;
  tick: number;
  speed: number;
  /** A 64-bit value, kept as a string: as a JSON number it would be rounded by the browser. */
  seed: string;
  systemName: string;
  parentRunId: string | null;
  forkedFromTick: number | null;
  watchers: number;
  error: string | null;
};

export type RunDetail = {
  summary: RunSummary;
  spec: SystemSpec | null;
  sampleCount: number;
  logEntryCount: number;
  ticksPerSecondActual: number;
  latestValues: Record<string, number>;
};

export type Checkpoint = {
  id: string;
  tick: number;
  label: string | null;
  stateBytes: number;
  automatic: boolean;
  createdAt: string;
};

export type SeriesDefinition = {
  id: number;
  seriesKey: string;
  objectId: string | null;
  variable: string | null;
  category: string;
};

/** Points arrive as `[tick, value]` pairs to keep long series compact on the wire. */
export type SeriesPoint = [number, number];

export type SeriesView = { key: string; points: SeriesPoint[]; bucketed: boolean };

export type SeriesResponse = {
  runId: string;
  fromTick: number;
  toTick: number;
  resolution: number;
  series: SeriesView[];
};

export type LogEntry = {
  id: number;
  tick: number;
  type: "EVENT" | "REACTIVATION" | "INTERACTION" | "CHECKPOINT" | "CONTROL" | "NOTE";
  subject: string | null;
  detail: string;
};

export type MemoryView = {
  id: number;
  ownerId: string;
  subjectId: string;
  kind: string;
  createdTick: number;
  initialStrength: number;
  currentStrength: number;
  valence: number;
  reactivationCount: number;
};

export type RelationshipCell = {
  ownerId: string;
  subjectId: string;
  meanValence: number;
  totalStrength: number;
  memoryCount: number;
};

export type Page<T> = { items: T[]; limit: number; hasMore: boolean };

/** What the live stream pushes on every flush. */
export type RunSnapshot = {
  runId: string;
  status: RunStatus;
  tick: number;
  speedTicksPerSecond: number;
  memoryCount: number;
  latestValues: Record<string, number>;
  ticksPerSecondActual: number;
};
