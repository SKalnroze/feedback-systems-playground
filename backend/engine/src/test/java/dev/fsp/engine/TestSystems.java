package dev.fsp.engine;

import dev.fsp.engine.event.Effect;
import dev.fsp.engine.event.EventGenerator;
import dev.fsp.engine.event.TargetSelector;
import dev.fsp.engine.expr.NumExpr;
import dev.fsp.engine.expr.Scope;
import dev.fsp.engine.graph.Transfer;
import dev.fsp.engine.memory.DecayModel;
import dev.fsp.engine.memory.MemorySettings;
import dev.fsp.engine.spec.EventSpec;
import dev.fsp.engine.spec.LinkSpec;
import dev.fsp.engine.spec.ObjectSpec;
import dev.fsp.engine.spec.ObjectTypeSpec;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.spec.VariableRef;
import dev.fsp.engine.spec.VariableSpec;
import java.util.List;
import java.util.Map;

/** Small hand-built systems used across the engine tests. */
final class TestSystems {

    static final MemorySettings MEMORY = new MemorySettings(DecayModel.Exponential.ofHalfLife(20.0), 0.05, 0, true,
            0.6);

    private TestSystems() {
    }

    static ObjectTypeSpec personType() {
        return new ObjectTypeSpec("person", "Person",
                List.of(VariableSpec.unitStock("trust", 0.5), VariableSpec.unitStock("resentment", 0.0),
                        VariableSpec.unitStock("withdrawal", 0.0)),
                MEMORY, java.util.Set.of(), Map.of());
    }

    /** Two people, no events, no links: the quietest possible system. */
    static SystemSpec pair() {
        return SystemSpec.builder("pair").objectType(personType()).object(ObjectSpec.of("alice", "person"))
                .object(ObjectSpec.of("bob", "person")).build();
    }

    /**
     * A reinforcing loop between resentment and withdrawal, plus a balancing link back from
     * withdrawal to trust with a delay.
     */
    static SystemSpec loopSystem(int delayTicks) {
        return SystemSpec.builder("loops").objectType(personType()).object(ObjectSpec.of("alice", "person"))
                .link(new LinkSpec("resentment-to-withdrawal", "resentment feeds withdrawal",
                        new VariableRef.OfObject("alice", "resentment"),
                        new VariableRef.OfObject("alice", "withdrawal"), 0.1, 0, Transfer.linear(), false))
                .link(new LinkSpec("withdrawal-to-resentment", "withdrawal feeds resentment",
                        new VariableRef.OfObject("alice", "withdrawal"),
                        new VariableRef.OfObject("alice", "resentment"), 0.1, 0, Transfer.linear(), false))
                .link(new LinkSpec("withdrawal-erodes-trust", "withdrawal erodes trust",
                        new VariableRef.OfObject("alice", "withdrawal"), new VariableRef.OfObject("alice", "trust"),
                        -0.2, delayTicks, Transfer.linear(), false))
                .build();
    }

    /** A scheduled shock that knocks trust down and leaves a memory behind. */
    static SystemSpec scheduledShock(long... ticks) {
        return SystemSpec.builder("shock").objectType(personType()).object(ObjectSpec.of("alice", "person"))
                .object(ObjectSpec.of("bob", "person"))
                .event(EventSpec.of("argument", EventGenerator.FixedSchedule.at(ticks), TargetSelector.RandomPair.one(),
                        List.of(Effect.AdjustVariable.add(Scope.SELF, "trust", -0.2),
                                new Effect.InjectMemory(Scope.SELF, Scope.TARGET, "interaction", NumExpr.of(0.9),
                                        NumExpr.of(-0.8), 0.8, Map.of("conflict", 1.0), null))))
                .build();
    }
}
