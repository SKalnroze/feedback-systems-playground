package dev.fsp.engine.state;

import dev.fsp.engine.memory.MemoryRecord;
import java.util.Map;
import java.util.StringJoiner;

/**
 * A stable textual fingerprint of simulation state.
 *
 * <p>Determinism is a promise the engine makes and therefore has to be able to prove. Comparing
 * digests is how a replay, a checkpoint restore and a fork are checked against the run they came
 * from, in tests and in the application's integrity checks.
 */
public final class StateDigest {

    private StateDigest() {
    }

    /** Full digest: tick, globals, every object's variables, every memory's current strength. */
    public static String of(SimulationState state) {
        StringJoiner digest = new StringJoiner("\n");
        digest.add("tick=" + state.tick());
        digest.add("seed=" + state.runSeed());

        StringJoiner globals = new StringJoiner(",");
        for (Map.Entry<String, Double> entry : state.globals().entrySet()) {
            globals.add(entry.getKey() + "=" + round(entry.getValue()));
        }
        digest.add("globals[" + globals + "]");

        for (ObjectState object : state.allObjects()) {
            StringJoiner variables = new StringJoiner(",");
            for (Map.Entry<String, Double> entry : object.variables().entrySet()) {
                variables.add(entry.getKey() + "=" + round(entry.getValue()));
            }
            digest.add(object.id() + "{" + variables + "}tags=" + object.tags() + ",active=" + object.isActive());
        }

        long tick = state.tick();
        StringJoiner memories = new StringJoiner(";");
        state.memories().forEach(record -> memories.add(memoryDigest(record, tick)));
        digest.add("memories[" + memories + "]");

        StringJoiner delays = new StringJoiner(",");
        for (Map.Entry<String, DelayLine> entry : state.delayLines().entrySet()) {
            StringJoiner pending = new StringJoiner("|");
            for (double value : entry.getValue().pending()) {
                pending.add(round(value));
            }
            delays.add(entry.getKey() + "=" + pending);
        }
        digest.add("delays[" + delays + "]");
        digest.add("generators" + state.generatorStates());

        return digest.toString();
    }

    private static String memoryDigest(MemoryRecord record, long tick) {
        return record.id() + ":" + record.ownerId() + ">" + record.subjectId() + ":" + record.kind() + ":"
                + round(record.strengthAt(tick)) + ":" + round(record.valence()) + ":" + record.reactivationCount();
    }

    /**
     * Rounds to nine decimal places. Two runs that agree to that precision agree for every purpose
     * the tool has, and exact bit equality would make the digest hostage to compiler-level
     * floating-point reassociation.
     */
    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.9f", value);
    }
}
