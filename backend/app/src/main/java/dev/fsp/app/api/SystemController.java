package dev.fsp.app.api;

import dev.fsp.app.api.ApiDtos.CreateFromPresetRequest;
import dev.fsp.app.api.ApiDtos.CreateSystemRequest;
import dev.fsp.app.api.ApiDtos.SaveDraftRequest;
import dev.fsp.app.api.ApiDtos.ValidateRequest;
import dev.fsp.app.api.SystemService.ValidationReport;
import dev.fsp.app.persistence.SystemRecords.SystemDefinition;
import dev.fsp.app.persistence.SystemRecords.SystemVersion;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Authoring: create a system, autosave the editor's draft, publish an immutable version. */
@RestController
@RequestMapping("/api/v1/systems")
public class SystemController {

    private final SystemService systems;

    public SystemController(SystemService systems) {
        this.systems = systems;
    }

    @GetMapping
    public List<SystemDefinition> list() {
        return systems.findAll();
    }

    @GetMapping("/{id}")
    public SystemDefinition get(@PathVariable UUID id) {
        return systems.find(id).orElseThrow(() -> new NotFoundException("system", id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SystemDefinition create(@Valid @RequestBody CreateSystemRequest request) {
        return systems.create(request.name(), request.description() == null ? "" : request.description(),
                request.draft());
    }

    /** Starts a new system from a module preset, ready to be modified. */
    @PostMapping("/from-preset")
    @ResponseStatus(HttpStatus.CREATED)
    public SystemDefinition createFromPreset(@Valid @RequestBody CreateFromPresetRequest request) {
        return systems.createFromPreset(request.presetId(), request.name());
    }

    /** The editor autosaves here; nothing is published until it is asked for explicitly. */
    @PutMapping("/{id}/draft")
    public SystemDefinition saveDraft(@PathVariable UUID id, @RequestBody SaveDraftRequest request) {
        systems.saveDraft(id, request.name(), request.description(), request.draft());
        return systems.find(id).orElseThrow(() -> new NotFoundException("system", id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        systems.delete(id);
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public SystemVersion publish(@PathVariable UUID id) {
        return systems.publish(id);
    }

    @GetMapping("/{id}/versions")
    public List<SystemVersion> versions(@PathVariable UUID id) {
        return systems.versions(id);
    }

    @GetMapping("/{id}/versions/latest")
    public SystemVersion latest(@PathVariable UUID id) {
        return systems.latestVersion(id).orElseThrow(() -> new NotFoundException("published version of system", id));
    }

    @GetMapping("/versions/{versionId}")
    public SystemVersion version(@PathVariable UUID versionId) {
        return systems.version(versionId).orElseThrow(() -> new NotFoundException("system version", versionId));
    }

    /**
     * Checks a spec without saving it. Called on every edit, so the editor can show problems and
     * the loop summary while the user is still working rather than at publish time.
     */
    @PostMapping("/validate")
    public ValidationReport validate(@RequestBody ValidateRequest request) {
        if (request.spec() == null) {
            throw new IllegalArgumentException("a spec is required");
        }
        return systems.validate(request.spec());
    }

    /** Validates the stored draft, which is what the editor polls while autosaving. */
    @GetMapping("/{id}/validation")
    public ValidationReport validateDraft(@PathVariable UUID id) {
        SystemDefinition definition = systems.find(id).orElseThrow(() -> new NotFoundException("system", id));
        if (definition.draft() == null) {
            throw new IllegalArgumentException("system " + id + " has no draft");
        }
        return systems.validate(definition.draft());
    }
}
