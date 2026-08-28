package dev.fsp.app.api;

import dev.fsp.app.modules.ModuleRegistry;
import dev.fsp.app.persistence.SystemRecords.SystemDefinition;
import dev.fsp.app.persistence.SystemRecords.SystemVersion;
import dev.fsp.app.persistence.SystemRepository;
import dev.fsp.engine.graph.FeedbackLoop;
import dev.fsp.engine.graph.LoopDetector;
import dev.fsp.engine.spec.SystemSpec;
import dev.fsp.engine.spec.SystemSpec.ValidationIssue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Creating, editing, validating and publishing system definitions. */
@Service
public class SystemService {

    private final SystemRepository systems;
    private final ModuleRegistry modules;

    public SystemService(SystemRepository systems, ModuleRegistry modules) {
        this.systems = systems;
        this.modules = modules;
    }

    public List<SystemDefinition> findAll() {
        return systems.findAll();
    }

    public Optional<SystemDefinition> find(UUID id) {
        return systems.find(id);
    }

    public SystemDefinition create(String name, String description, SystemSpec draft) {
        UUID id = systems.create(name, description, draft);
        return systems.find(id).orElseThrow();
    }

    /** Copies a module preset into a new editable system. */
    public SystemDefinition createFromPreset(String presetId, String name) {
        var preset = modules.preset(presetId)
                .orElseThrow(() -> new IllegalArgumentException("no such preset: " + presetId));
        String systemName = name == null || name.isBlank() ? preset.label() : name;
        return create(systemName, preset.description(), preset.spec());
    }

    public void saveDraft(UUID id, String name, String description, SystemSpec draft) {
        SystemDefinition existing = systems.find(id)
                .orElseThrow(() -> new IllegalArgumentException("no such system: " + id));
        systems.saveDraft(id, name == null ? existing.name() : name,
                description == null ? existing.description() : description, draft);
    }

    public void delete(UUID id) {
        systems.delete(id);
    }

    /**
     * Publishes the current draft as an immutable version.
     *
     * <p>Refuses on validation errors. A run started from a broken spec would fail at some
     * arbitrary tick with a stack trace instead of at publish time with a message pointing at the
     * offending node.
     */
    public SystemVersion publish(UUID id) {
        SystemDefinition definition = systems.find(id)
                .orElseThrow(() -> new IllegalArgumentException("no such system: " + id));
        if (definition.draft() == null) {
            throw new IllegalArgumentException("system " + id + " has no draft to publish");
        }
        List<ValidationIssue> errors = definition.draft().validate().stream().filter(ValidationIssue::isError)
                .toList();
        if (!errors.isEmpty()) {
            throw new SpecValidationException(errors);
        }
        return systems.publish(id, definition.draft());
    }

    public List<SystemVersion> versions(UUID id) {
        return systems.findVersions(id);
    }

    public Optional<SystemVersion> version(UUID versionId) {
        return systems.findVersion(versionId);
    }

    public Optional<SystemVersion> latestVersion(UUID systemId) {
        return systems.findLatestVersion(systemId);
    }

    /**
     * Checks a spec without storing it, and reports the feedback loops it contains.
     *
     * <p>The loops matter as much as the errors: the editor draws them, and seeing "two reinforcing
     * loops, one balancing loop delayed by five ticks" is usually what tells an author whether they
     * have built what they meant to.
     */
    public ValidationReport validate(SystemSpec spec) {
        List<ValidationIssue> issues = spec.validate();
        List<FeedbackLoop> loops = new LoopDetector(spec.links()).detect();
        List<String> missingRules = spec.interactions().stream().map(config -> config.ruleId())
                .filter(ruleId -> modules.rule(ruleId).isEmpty()).toList();
        return new ValidationReport(issues.stream().noneMatch(ValidationIssue::isError) && missingRules.isEmpty(),
                issues, loops, missingRules);
    }

    /**
     * @param valid        true when the spec can be published and run
     * @param loops        feedback loops found in the link graph, with polarity and delay
     * @param missingRules interaction rules the spec asks for that no installed module provides
     */
    public record ValidationReport(boolean valid, List<ValidationIssue> issues, List<FeedbackLoop> loops,
            List<String> missingRules) {
    }

    /** Thrown when a publish is refused; carries the issues so the editor can highlight them. */
    public static class SpecValidationException extends RuntimeException {

        private final transient List<ValidationIssue> issues;

        public SpecValidationException(List<ValidationIssue> issues) {
            super("system specification has " + issues.size() + " error(s)");
            this.issues = List.copyOf(issues);
        }

        public List<ValidationIssue> issues() {
            return issues;
        }
    }
}
