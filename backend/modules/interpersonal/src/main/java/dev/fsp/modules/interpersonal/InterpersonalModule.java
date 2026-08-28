package dev.fsp.modules.interpersonal;

import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.module.SimulationModule;
import dev.fsp.engine.spec.ObjectTypeSpec;
import java.util.List;

/**
 * Interpersonal relationships: people who meet, help each other, fall out, talk about each other,
 * and remember all of it imperfectly.
 *
 * <p>The first module, and the reference for what a module is: it contributes an object type, a
 * handful of behaviours and some worked examples, and nothing in the engine is aware of any of it.
 */
public final class InterpersonalModule implements SimulationModule {

    public static final String ID = "interpersonal";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Interpersonal relationships";
    }

    @Override
    public String description() {
        return "Groups of people whose attitudes are driven by what they remember of each other.";
    }

    @Override
    public List<ObjectTypeSpec> objectTypes() {
        return List.of(Person.type());
    }

    @Override
    public List<InteractionRule> interactionRules() {
        return List.of(new MeetRule(), new ConflictRule(), new FavourRule(), new GossipRule());
    }

    @Override
    public List<Preset> presets() {
        return InterpersonalPresets.all();
    }
}
