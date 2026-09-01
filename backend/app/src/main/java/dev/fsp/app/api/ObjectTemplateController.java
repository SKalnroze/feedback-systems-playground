package dev.fsp.app.api;

import dev.fsp.app.persistence.ObjectTemplateRepository;
import dev.fsp.app.persistence.ObjectTemplateRepository.Template;
import dev.fsp.app.persistence.ObjectTemplateRepository.TemplateVersion;
import dev.fsp.engine.spec.ObjectTypeSpec;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Object types that live outside any one system.
 *
 * <p>A type defined inside a system cannot be used by the next one, so every model starts by
 * redefining the same vocabulary. A template is that vocabulary published once and reused, and
 * versioned for the same reason systems are: a system that was built and run against one shape of
 * "person" must not silently acquire a different one.
 */
@RestController
@RequestMapping("/api/v1/object-templates")
public class ObjectTemplateController {

    private final ObjectTemplateRepository templates;

    public ObjectTemplateController(ObjectTemplateRepository templates) {
        this.templates = templates;
    }

    public record SaveTemplate(String name, String description, ObjectTypeSpec draft) {
    }

    /**
     * @param outdated true when the type embedded in a system is older than the template's latest
     * @param latest   the newest published version number, or null when nothing is published yet
     */
    public record TemplateUsage(String templateId, int usedVersion, Integer latest, boolean outdated) {
    }

    @GetMapping
    public List<Template> list(@RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return templates.findAll(Math.clamp(limit, 1, 500), Math.max(0, offset));
    }

    @GetMapping("/{id}")
    public Template get(@PathVariable UUID id) {
        return templates.find(id).orElseThrow(() -> new NotFoundException("object template", id));
    }

    @PostMapping
    public Template create(@RequestBody SaveTemplate request) {
        UUID id = templates.create(request.name() == null || request.name().isBlank() ? "Untitled type"
                : request.name(), request.description() == null ? "" : request.description(), request.draft());
        return templates.find(id).orElseThrow();
    }

    @PutMapping("/{id}/draft")
    public Template saveDraft(@PathVariable UUID id, @RequestBody SaveTemplate request) {
        templates.find(id).orElseThrow(() -> new NotFoundException("object template", id));
        templates.saveDraft(id, request.name(), request.description() == null ? "" : request.description(),
                request.draft());
        return templates.find(id).orElseThrow();
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        templates.delete(id);
    }

    /** Freezes the current draft as a version systems can pin to. */
    @PostMapping("/{id}/versions")
    public TemplateVersion publish(@PathVariable UUID id) {
        Template template = templates.find(id).orElseThrow(() -> new NotFoundException("object template", id));
        if (template.draft() == null) {
            throw new IllegalArgumentException("template " + id + " has no draft to publish");
        }
        return templates.publish(id, template.draft());
    }

    @GetMapping("/{id}/versions")
    public List<TemplateVersion> versions(@PathVariable UUID id) {
        return templates.findVersions(id);
    }

    @GetMapping("/{id}/versions/latest")
    public TemplateVersion latest(@PathVariable UUID id) {
        return templates.findLatestVersion(id)
                .orElseThrow(() -> new NotFoundException("published version of object template", id));
    }

    /**
     * What changed between the version a system embedded and another one.
     *
     * <p>Answering "your type is out of date" without saying what moved leaves the author to diff
     * two JSON documents by eye, which is exactly the work the tool exists to avoid.
     */
    @GetMapping("/{id}/versions/{from}/diff/{to}")
    public SpecDiff.Report diff(@PathVariable UUID id, @PathVariable int from, @PathVariable int to) {
        ObjectTypeSpec before = templates.findVersionNumbered(id, from)
                .orElseThrow(() -> new NotFoundException("object template version " + from, id)).typeSpec();
        ObjectTypeSpec after = templates.findVersionNumbered(id, to)
                .orElseThrow(() -> new NotFoundException("object template version " + to, id)).typeSpec();
        return SpecDiff.betweenTypes(before, after);
    }
}
