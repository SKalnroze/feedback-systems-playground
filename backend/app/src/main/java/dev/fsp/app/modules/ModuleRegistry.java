package dev.fsp.app.modules;

import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.module.SimulationModule;
import dev.fsp.engine.spec.ObjectTypeSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import org.springframework.stereotype.Component;

/**
 * The modules available to this installation, discovered from the classpath.
 *
 * <p>{@link ServiceLoader} rather than component scanning, so a domain pack stays a plain jar with
 * no dependency on Spring - the same jar can be used from a test or a future command-line runner.
 */
@Component
public class ModuleRegistry {

    private final Map<String, SimulationModule> modules = new LinkedHashMap<>();
    private final Map<String, InteractionRule> rules = new LinkedHashMap<>();

    public ModuleRegistry() {
        this(ServiceLoader.load(SimulationModule.class).stream().map(ServiceLoader.Provider::get).toList());
    }

    /** Explicit constructor for tests, which need to control exactly which modules are present. */
    public ModuleRegistry(List<SimulationModule> discovered) {
        for (SimulationModule module : discovered) {
            modules.put(module.id(), module);
            for (InteractionRule rule : module.interactionRules()) {
                rules.put(rule.id(), rule);
            }
        }
    }

    public List<SimulationModule> modules() {
        return List.copyOf(modules.values());
    }

    public Optional<SimulationModule> module(String id) {
        return Optional.ofNullable(modules.get(id));
    }

    /** Every interaction rule from every module, which is what the engine needs to run a system. */
    public List<InteractionRule> allRules() {
        return List.copyOf(rules.values());
    }

    public Optional<InteractionRule> rule(String id) {
        return Optional.ofNullable(rules.get(id));
    }

    /** Every object type any module offers, for the editor palette. */
    public List<ObjectTypeSpec> allObjectTypes() {
        List<ObjectTypeSpec> types = new ArrayList<>();
        modules.values().forEach(module -> types.addAll(module.objectTypes()));
        return List.copyOf(types);
    }

    public List<SimulationModule.Preset> allPresets() {
        List<SimulationModule.Preset> presets = new ArrayList<>();
        modules.values().forEach(module -> presets.addAll(module.presets()));
        return List.copyOf(presets);
    }

    public Optional<SimulationModule.Preset> preset(String id) {
        return allPresets().stream().filter(preset -> preset.id().equals(id)).findFirst();
    }
}
