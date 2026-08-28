# Why a run always produces the same numbers

Reproducibility is not a nice property here, it is the product. Comparing two configurations only
means something if the difference between them is the *only* difference. Forking a run to try
another continuation only means something if the shared history really was shared. So the engine is
built so that a run's output is a pure function of its specification and its seed.

Three decisions make that true, and each of them costs something.

## Randomness comes from coordinates, not from a generator

The obvious design is one seeded `Random` per run, carried forward. It is also wrong for this
tool: the sequence then depends on how many draws happened before, which depends on iteration
order, on how many objects existed at the time, and on whether a phase that happened to draw a
number was reached at all. Add a draw anywhere and everything downstream shifts.

Instead every draw derives its own seed:

```
seed = SplitMix64(runSeed, tick, streamId, entityId)
```

A draw is therefore reproducible in isolation. Adding a new random decision to the interaction
phase cannot change what the event phase drew, because they are on different streams. Restarting
the process changes nothing, because nothing is carried forward.

The cost is that generators which genuinely depend on their own history — a Markov chain's current
regime, a self-exciting process's accumulated intensity — must carry that state explicitly, in
`GeneratorState`, where it gets checkpointed like everything else. That is the honest place for it.

There is one subtlety worth knowing about, because it was a real bug. A jittered periodic event
must answer "which tick does period 7 land on" identically no matter which tick is asking, so it
draws from a stream keyed by the *period index*, not the tick. Keying it by tick made every tick
compute a different answer for the same period, and the event fired at roughly the right rate while
being wrong in a way that only showed up as a count being slightly off.

## A checkpoint stores state, not the future

Because randomness is derived, a checkpoint needs only the tick, the seed and the state. It does
not need a generator's internal buffer, and there is no way for a restored run to drift from the
run it came from.

`EngineSnapshot` is written out as an explicit record rather than by serialising the engine's own
state classes. Those classes are mutable and tuned for speed, and their fields will change; the
stored format should not have to change with them. The engine's `StateDigest` exists to prove the
round trip: several tests run a simulation straight through, run a second one that stops and
resumes at the halfway point, and assert the two digests are identical.

## The engine cannot reach anything ambient

An ArchUnit test forbids the engine from depending on Spring, Jakarta, a serialisation library, the
filesystem, `java.util.Random`, `Math.random`, `System.nanoTime` or `java.time.Instant`. Every one
of those is a way for a result to depend on something other than the specification and the seed.

Simulated time is the tick counter and nothing else. If the engine could read the clock, two runs of
the same seed would differ, and no test could tell you why.

## What is deliberately *not* deterministic

Wall-clock pacing. A run asked for twenty ticks a second gets approximately twenty ticks a second,
and the actual rate depends on the machine. This does not affect results at all: pacing decides
*when* a tick happens, never *what* it does. That separation is why the speed control can be
changed mid-run without invalidating anything.

Control commands are applied at tick boundaries for the same reason. A pause that took effect
halfway through a tick would make the outcome depend on when a button was pressed.
