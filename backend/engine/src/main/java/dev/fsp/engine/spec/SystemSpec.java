package dev.fsp.engine.spec;

import dev.fsp.engine.memory.MemoryTrigger;
import dev.fsp.engine.module.InteractionRule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A complete, runnable definition of a feedback system.
 *
 * <p>This is the authored artefact: what the visual editor produces, what gets versioned, and the
 * only input the engine needs besides a seed. It is immutable, so a run always knows exactly what
 * it was started from.
 */
public record SystemSpec(String id, String name, String description, List<String> moduleIds,
        List<ObjectTypeSpec> objectTypes, List<ObjectSpec> objects, List<VariableSpec> globalVariables,
        List<LinkSpec> links, List<EventSpec> events, List<MemoryTrigger> triggers,
        List<InteractionRule.Config> interactions, SimulationSettings settings) {

    public SystemSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("system id must not be blank");
        }
        moduleIds = List.copyOf(moduleIds);
        objectTypes = List.copyOf(objectTypes);
        objects = List.copyOf(objects);
        globalVariables = List.copyOf(globalVariables);
        links = List.copyOf(links);
        events = List.copyOf(events);
        triggers = List.copyOf(triggers);
        interactions = List.copyOf(interactions);
        settings = settings == null ? SimulationSettings.DEFAULT : settings;
        name = name == null || name.isBlank() ? id : name;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public ObjectTypeSpec objectType(String typeId) {
        for (ObjectTypeSpec type : objectTypes) {
            if (type.id().equals(typeId)) {
                return type;
            }
        }
        throw new IllegalArgumentException("no such object type: " + typeId);
    }

    public boolean hasObjectType(String typeId) {
        for (ObjectTypeSpec type : objectTypes) {
            if (type.id().equals(typeId)) {
                return true;
            }
        }
        return false;
    }

    public EventSpec event(String eventId) {
        for (EventSpec event : events) {
            if (event.id().equals(eventId)) {
                return event;
            }
        }
        throw new IllegalArgumentException("no such event: " + eventId);
    }

    public VariableSpec globalVariable(String name) {
        for (VariableSpec variable : globalVariables) {
            if (variable.name().equals(name)) {
                return variable;
            }
        }
        throw new IllegalArgumentException("no such global variable: " + name);
    }

    public boolean hasGlobalVariable(String name) {
        for (VariableSpec variable : globalVariables) {
            if (variable.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks the spec for anything that would fail or silently misbehave at run time: dangling
     * references, duplicate ids, links into constants. Returns an empty list when the spec is
     * sound.
     */
    public List<ValidationIssue> validate() {
        List<ValidationIssue> issues = new ArrayList<>();
        checkDuplicateIds(issues);
        checkObjects(issues);
        checkLinks(issues);
        checkEvents(issues);
        checkTriggers(issues);
        GroupValidation.check(this, issues);
        return List.copyOf(issues);
    }

    /** True when {@link #validate()} finds nothing. */
    public boolean isValid() {
        return validate().isEmpty();
    }

    private void checkDuplicateIds(List<ValidationIssue> issues) {
        reportDuplicates(issues, "objectType", objectTypes.stream().map(ObjectTypeSpec::id).toList());
        reportDuplicates(issues, "object", objects.stream().map(ObjectSpec::id).toList());
        reportDuplicates(issues, "globalVariable", globalVariables.stream().map(VariableSpec::name).toList());
        reportDuplicates(issues, "link", links.stream().map(LinkSpec::id).toList());
        reportDuplicates(issues, "event", events.stream().map(EventSpec::id).toList());
        reportDuplicates(issues, "trigger", triggers.stream().map(MemoryTrigger::id).toList());
    }

    private void reportDuplicates(List<ValidationIssue> issues, String kind, List<String> ids) {
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                issues.add(ValidationIssue.error(kind, id, "duplicate " + kind + " id: " + id));
            }
        }
    }

    private void checkObjects(List<ValidationIssue> issues) {
        for (ObjectSpec object : objects) {
            if (!hasObjectType(object.typeId())) {
                issues.add(ValidationIssue.error("object", object.id(),
                        "references unknown object type " + object.typeId()));
                continue;
            }
            ObjectTypeSpec type = objectType(object.typeId());
            for (Map.Entry<String, Double> entry : object.variables().entrySet()) {
                if (!type.hasVariable(entry.getKey())) {
                    issues.add(ValidationIssue.error("object", object.id(),
                            "sets variable " + entry.getKey() + " which type " + type.id() + " does not declare"));
                    continue;
                }
                VariableSpec variable = type.variable(entry.getKey());
                if (entry.getValue() < variable.min() || entry.getValue() > variable.max()) {
                    issues.add(ValidationIssue.warning("object", object.id(), "initial value for " + entry.getKey()
                            + " lies outside the declared bounds and will be clamped"));
                }
            }
        }
    }

    private void checkLinks(List<ValidationIssue> issues) {
        for (LinkSpec link : links) {
            checkVariableRef(issues, "link", link.id(), link.source(), false);
            checkVariableRef(issues, "link", link.id(), link.target(), true);
        }
    }

    private void checkVariableRef(List<ValidationIssue> issues, String kind, String id, VariableRef ref,
            boolean asTarget) {
        switch (ref) {
            case VariableRef.Global global -> {
                if (!hasGlobalVariable(global.name())) {
                    issues.add(ValidationIssue.error(kind, id, "unknown global variable " + global.name()));
                } else if (asTarget && !globalVariable(global.name()).isWritable()) {
                    issues.add(ValidationIssue.error(kind, id,
                            "cannot write to constant global " + global.name()));
                }
            }
            case VariableRef.OfObject ofObject -> {
                ObjectSpec object = findObject(ofObject.objectId());
                if (object == null) {
                    issues.add(ValidationIssue.error(kind, id, "unknown object " + ofObject.objectId()));
                } else if (hasObjectType(object.typeId())) {
                    ObjectTypeSpec type = objectType(object.typeId());
                    if (!type.hasVariable(ofObject.name())) {
                        issues.add(ValidationIssue.error(kind, id,
                                "object " + object.id() + " has no variable " + ofObject.name()));
                    } else if (asTarget && !type.variable(ofObject.name()).isWritable()) {
                        issues.add(ValidationIssue.error(kind, id,
                                "cannot write to constant variable " + ofObject.name()));
                    }
                }
            }
            case VariableRef.OfType ofType -> {
                if (!hasObjectType(ofType.typeId())) {
                    issues.add(ValidationIssue.error(kind, id, "unknown object type " + ofType.typeId()));
                } else if (!objectType(ofType.typeId()).hasVariable(ofType.name())) {
                    issues.add(ValidationIssue.error(kind, id,
                            "object type " + ofType.typeId() + " has no variable " + ofType.name()));
                }
            }
        }
    }

    private void checkEvents(List<ValidationIssue> issues) {
        for (EventSpec event : events) {
            if (event.effects().isEmpty()) {
                issues.add(ValidationIssue.warning("event", event.id(), "has no effects and will do nothing"));
            }
        }
    }

    private void checkTriggers(List<ValidationIssue> issues) {
        Set<String> eventIds = new HashSet<>();
        for (EventSpec event : events) {
            eventIds.add(event.id());
        }
        for (MemoryTrigger trigger : triggers) {
            if (trigger.effect().emitsEvent() && !eventIds.contains(trigger.effect().emitEventId())) {
                issues.add(ValidationIssue.error("trigger", trigger.id(),
                        "emits unknown event " + trigger.effect().emitEventId()));
            }
        }
    }

    private ObjectSpec findObject(String objectId) {
        for (ObjectSpec object : objects) {
            if (object.id().equals(objectId)) {
                return object;
            }
        }
        return null;
    }

    /** A problem found by {@link #validate()}. */
    public record ValidationIssue(Severity severity, String elementKind, String elementId, String message) {

        public enum Severity {
            ERROR,
            WARNING
        }

        public static ValidationIssue error(String elementKind, String elementId, String message) {
            return new ValidationIssue(Severity.ERROR, elementKind, elementId, message);
        }

        public static ValidationIssue warning(String elementKind, String elementId, String message) {
            return new ValidationIssue(Severity.WARNING, elementKind, elementId, message);
        }

        public boolean isError() {
            return severity == Severity.ERROR;
        }

        @Override
        public String toString() {
            return severity + " " + elementKind + "[" + elementId + "]: " + message;
        }
    }

    /** Fluent builder; specs have too many optional collections for a readable constructor call. */
    public static final class Builder {

        private final String id;
        private String name;
        private String description = "";
        private final List<String> moduleIds = new ArrayList<>();
        private final List<ObjectTypeSpec> objectTypes = new ArrayList<>();
        private final List<ObjectSpec> objects = new ArrayList<>();
        private final List<VariableSpec> globalVariables = new ArrayList<>();
        private final List<LinkSpec> links = new ArrayList<>();
        private final List<EventSpec> events = new ArrayList<>();
        private final List<MemoryTrigger> triggers = new ArrayList<>();
        private final List<InteractionRule.Config> interactions = new ArrayList<>();
        private SimulationSettings settings = SimulationSettings.DEFAULT;

        private Builder(String id) {
            this.id = id;
            this.name = id;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder module(String moduleId) {
            moduleIds.add(moduleId);
            return this;
        }

        public Builder objectType(ObjectTypeSpec type) {
            objectTypes.add(type);
            return this;
        }

        public Builder object(ObjectSpec object) {
            objects.add(object);
            return this;
        }

        public Builder globalVariable(VariableSpec variable) {
            globalVariables.add(variable);
            return this;
        }

        public Builder link(LinkSpec link) {
            links.add(link);
            return this;
        }

        public Builder event(EventSpec event) {
            events.add(event);
            return this;
        }

        public Builder trigger(MemoryTrigger trigger) {
            triggers.add(trigger);
            return this;
        }

        public Builder interaction(InteractionRule.Config config) {
            interactions.add(config);
            return this;
        }

        public Builder settings(SimulationSettings settings) {
            this.settings = settings;
            return this;
        }

        /** Adds several objects of one type with sequential ids, for quick group setups. */
        public Builder objects(String typeId, String... ids) {
            for (String objectId : ids) {
                objects.add(ObjectSpec.of(objectId, typeId));
            }
            return this;
        }

        public SystemSpec build() {
            return new SystemSpec(id, name, description, moduleIds, objectTypes, objects, globalVariables, links,
                    events, triggers, interactions, settings);
        }
    }

    /** Every series key this spec can produce, for the editor's series picker. */
    public Map<String, String> seriesCatalogue() {
        Map<String, String> catalogue = new LinkedHashMap<>();
        for (VariableSpec variable : globalVariables) {
            catalogue.put(new VariableRef.Global(variable.name()).seriesKey(), variable.label());
        }
        for (ObjectSpec object : objects) {
            if (!hasObjectType(object.typeId())) {
                continue;
            }
            for (VariableSpec variable : objectType(object.typeId()).variables()) {
                catalogue.put(new VariableRef.OfObject(object.id(), variable.name()).seriesKey(),
                        object.label() + " " + variable.label());
            }
        }
        return catalogue;
    }
}
