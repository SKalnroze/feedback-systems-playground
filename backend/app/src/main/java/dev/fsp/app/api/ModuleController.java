package dev.fsp.app.api;

import dev.fsp.app.api.ApiDtos.ModuleView;
import dev.fsp.app.api.ApiDtos.PaletteView;
import dev.fsp.app.api.ApiDtos.PresetView;
import dev.fsp.app.api.ApiDtos.RuleView;
import dev.fsp.app.json.EngineJson;
import dev.fsp.app.modules.ModuleRegistry;
import dev.fsp.engine.module.InteractionRule;
import dev.fsp.engine.module.SimulationModule;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this installation can model.
 *
 * <p>The editor builds its entire palette from these responses rather than from a hard-coded list,
 * so installing a module is all it takes for its object types and behaviours to appear in the UI.
 */
@RestController
@RequestMapping("/api/v1")
public class ModuleController {

    private final ModuleRegistry modules;

    public ModuleController(ModuleRegistry modules) {
        this.modules = modules;
    }

    @GetMapping("/modules")
    public List<ModuleView> modules() {
        List<ModuleView> views = new ArrayList<>();
        for (SimulationModule module : modules.modules()) {
            views.add(new ModuleView(module.id(), module.label(), module.description(), module.objectTypes(),
                    module.interactionRules().stream().map(ModuleController::toRuleView).toList(),
                    module.presets().stream()
                            .map(preset -> new PresetView(preset.id(), preset.label(), preset.description(),
                                    module.id()))
                            .toList()));
        }
        return views;
    }

    @GetMapping("/presets")
    public List<PresetView> presets() {
        List<PresetView> views = new ArrayList<>();
        for (SimulationModule module : modules.modules()) {
            module.presets().forEach(preset -> views
                    .add(new PresetView(preset.id(), preset.label(), preset.description(), module.id())));
        }
        return views;
    }

    /** The full spec of one preset, for previewing before it is copied into a new system. */
    @GetMapping("/presets/{presetId}")
    public SimulationModule.Preset preset(@PathVariable String presetId) {
        return modules.preset(presetId).orElseThrow(() -> new NotFoundException("preset", presetId));
    }

    /** Everything the visual editor needs to populate its node palette. */
    @GetMapping("/palette")
    public PaletteView palette() {
        return new PaletteView(EngineJson.kindsByHierarchy(), modules.allObjectTypes(),
                modules.allRules().stream().map(ModuleController::toRuleView).toList());
    }

    private static RuleView toRuleView(InteractionRule rule) {
        return new RuleView(rule.id(), rule.label(), rule.description(), rule.parameters());
    }
}
