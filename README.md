# Feedback Systems Playground

Define feedback systems, run them in the background, and look at what they did.

The distinguishing idea is **memory**. Objects in a system do not merely hold state — they hold
records of what happened to them, each fading on its own curve, and each able to be brought back by
something that happens later. A grudge that resurfaces every time two people meet never decays
away, and that loop, not any single variable, is usually what determines how the system behaves.

Interpersonal relationships are the first module shipped with it. Nothing in the engine knows about
people: it knows about objects, variables, memories, events and links.

---

## Running it

```bash
docker compose up --build -d
```

Then open <http://localhost:3000>. The API is on <http://localhost:8080>, Postgres on 5432.

```bash
docker compose logs -f backend    # watch the engine
docker compose down               # stop, keeping the database
docker compose down -v            # stop and discard everything
```

### Developing

```bash
docker compose -f compose.dev.yaml up -d    # Postgres and Adminer only
cd backend && ./gradlew :app:bootRun        # API on :8080, hot restart on rebuild
cd frontend && pnpm dev                     # UI on :5173, proxying /api to :8080
```

Requirements: JDK 25, Node 20.19+, pnpm, Docker.

---

## What you can build

A **system** is authored in the visual editor and published as an immutable version. Runs point at
a version, so editing a system can never change what a finished run meant.

| Piece | What it is |
|---|---|
| **Object type** | A kind of thing, with its variables and its memory settings |
| **Object** | An instance: a person, a team, a department |
| **Variable** | A stock that accumulates, an auxiliary recomputed each tick, or a constant |
| **Link** | A causal edge between two variables, with gain, delay and a response shape |
| **Event** | Something external, arriving on a schedule or a probability distribution |
| **Memory trigger** | A rule for what brings a memory back, and what that does to it |
| **Behaviour** | A module-supplied rule for what objects do among themselves |

### Forgetting curves

Eight, because the choice genuinely changes what a system does:

| Model | Shape |
|---|---|
| `none` | Never fades |
| `exponential` | A fixed proportion lost per tick |
| `power-law` | Fast early loss, then a very long tail (Wickelgren, Wixted) |
| `ebbinghaus` | Exponential, with stability growing per rehearsal — the spacing effect |
| `linear` | A constant amount lost per tick |
| `logistic` | Holds, then falls away around a horizon |
| `step-threshold` | Drops in stages: vivid, then the gist, then a name |
| `act-r-base-level` | Recency and frequency together; spaced rehearsal wins for free |

All of them share a **floor** (a permanent trace), a **consolidation** gain per reactivation, and
optional **interference** from similar memories.

### Bringing memories back

Triggers fire on an event, on the subject being present, on a condition over variables, on
similarity to what is happening now, periodically, at random, or on a memory's strength falling
into a band — combined with `all` / `any` / `not`. Firing can strengthen a memory, restart its
decay clock, slow its future decay, sour or soften how it feels, spawn a memory of the recall
itself, or set off an event.

### External events

From nothing at all to self-exciting clusters: `never`, `fixed-schedule`, `bernoulli`, `poisson`,
`periodic` (with jitter), `markov-chain` (regime switching), and `burst` (a Hawkes-style process
where each occurrence makes the next likelier).

---

## Running a simulation

Runs live in the background on their own thread. They can be started, paused, stepped by an exact
number of ticks, sped up to a chosen rate or let loose, and left alone for as long as you like.

**Checkpoints** save the complete state. Restoring rewinds a run; forking branches a separate run
from that point, which is how the tool answers "what if that argument had not happened" — run on,
fork back, change one event, and compare the two on the same chart.

Every run is **exactly reproducible**. Randomness is derived from `(seed, tick, stream, entity)`
rather than from a generator carried forward, so a run resumed from a checkpoint produces precisely
the numbers it would have produced had it never stopped. The test suite asserts this rather than
assuming it.

---

## Looking at what happened

- **Charts** — several stacked panels over one shared time axis, each with any number of series.
  Long ranges are bucketed in SQL rather than sent whole. There is deliberately no second y-axis.
- **Log** — every event, reactivation and interaction, filterable, and clicking an entry moves the
  chart cursor to that tick.
- **Memories** — what each object currently holds, how strong it is, how it feels, how often it has
  been recalled.
- **Relationship matrix** — who currently thinks what of whom, derived from stored memories rather
  than from a stored relationship number.

---

## How it is put together

```
backend/
  engine/                 pure Java 25 — no Spring, no persistence, no clock, no I/O
  modules/interpersonal/  the first domain pack: people, meetings, conflict, gossip
  app/                    Spring Boot 4 — REST, SSE, Postgres, run orchestration
frontend/                 React 19, Vite, TanStack Query/Router, ECharts, xyflow
docker/                   Dockerfiles and nginx config
```

The engine is a plain library, and an ArchUnit test keeps it that way: it may not depend on Spring,
Jakarta, a serialisation library, the filesystem, the wall clock, or any unseeded random source.
That is what lets the same code run under a unit test, a background run and a future CLI, and it is
what makes determinism testable at all.

Adding a domain is adding a module: implement `SimulationModule`, drop the jar on the classpath, and
its object types, behaviours and presets appear in the editor.

### Testing

```bash
cd backend && ./gradlew build        # unit, property, architecture, integration, acceptance
cd frontend && pnpm test             # component and logic tests
cd frontend && pnpm e2e              # end-to-end, against a running stack
```

- Engine: JUnit and jqwik, with a 90% line-coverage gate. Properties assert the invariants that
  matter — strength never grows without rehearsal, replay is identical, a restored checkpoint
  continues bit-for-bit.
- Application: Cucumber scenarios driving the real HTTP API against a real Postgres in
  Testcontainers. They read as descriptions of the product, in `app/src/test/resources/features`.
- Frontend: Vitest for logic, Playwright for the path a user actually takes.

---

## Presets

Four, each built to make one dynamic visible within a few hundred ticks:

- **Office team of eight** — ordinary contact, gossip, and a deadline crunch at tick 120.
- **Friends drifting apart** — no events at all, so only forgetting is at work.
- **A newcomer joins** — familiarity and reputation built from nothing.
- **Family with long memories** — a permanent floor and a gathering every sixty ticks that revives
  everything, the clearest demonstration of reactivation beating decay.
