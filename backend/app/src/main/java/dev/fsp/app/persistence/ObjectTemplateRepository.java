package dev.fsp.app.persistence;

import dev.fsp.engine.spec.ObjectTypeSpec;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Object type templates and their published versions.
 *
 * <p>Deliberately the same shape as {@link SystemRepository}: draft, publish, immutable versions,
 * checksum to recognise a republish of something unchanged. Templates have exactly the problem
 * systems already solved, and a second, subtly different answer to it would be a thing to remember
 * rather than a thing to know.
 */
@Repository
public class ObjectTemplateRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ObjectTemplateRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A template and the type it currently holds as a draft. */
    public record Template(UUID id, String name, String description, ObjectTypeSpec draft, Instant createdAt,
            Instant updatedAt) {
    }

    public record TemplateVersion(UUID id, UUID templateId, int version, ObjectTypeSpec typeSpec, String checksum,
            Instant publishedAt) {
    }

    public UUID create(String name, String description, ObjectTypeSpec draft) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO object_template (id, name, description, draft_spec)
                VALUES (:id, :name, :description, CAST(:draft AS jsonb))
                """).param("id", id).param("name", name).param("description", description)
                .param("draft", jsonb(draft)).update();
        return id;
    }

    public List<Template> findAll(int limit, int offset) {
        return jdbc.sql("""
                SELECT id, name, description, draft_spec, created_at, updated_at
                FROM object_template ORDER BY updated_at DESC LIMIT :limit OFFSET :offset
                """).param("limit", limit).param("offset", offset).query(this::mapTemplate).list();
    }

    public Optional<Template> find(UUID id) {
        return jdbc.sql("""
                SELECT id, name, description, draft_spec, created_at, updated_at
                FROM object_template WHERE id = :id
                """).param("id", id).query(this::mapTemplate).optional();
    }

    public void saveDraft(UUID id, String name, String description, ObjectTypeSpec draft) {
        jdbc.sql("""
                UPDATE object_template
                SET name = :name, description = :description, draft_spec = CAST(:draft AS jsonb), updated_at = now()
                WHERE id = :id
                """).param("id", id).param("name", name).param("description", description)
                .param("draft", jsonb(draft)).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM object_template WHERE id = :id").param("id", id).update();
    }

    /** Publishes a version, unless one identical to it is already the latest. */
    public TemplateVersion publish(UUID templateId, ObjectTypeSpec typeSpec) {
        String checksum = checksum(typeSpec);
        Optional<TemplateVersion> latest = findLatestVersion(templateId);
        if (latest.isPresent() && latest.get().checksum().equals(checksum)) {
            return latest.get();
        }

        int nextVersion = latest.map(TemplateVersion::version).orElse(0) + 1;
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO object_template_version (id, template_id, version, type_spec, checksum)
                VALUES (:id, :templateId, :version, CAST(:spec AS jsonb), :checksum)
                """).param("id", id).param("templateId", templateId).param("version", nextVersion)
                .param("spec", jsonb(typeSpec)).param("checksum", checksum).update();

        return findVersion(id).orElseThrow(() -> new IllegalStateException("version vanished after insert"));
    }

    public Optional<TemplateVersion> findVersion(UUID versionId) {
        return jdbc.sql("""
                SELECT id, template_id, version, type_spec, checksum, published_at
                FROM object_template_version WHERE id = :id
                """).param("id", versionId).query(this::mapVersion).optional();
    }

    public Optional<TemplateVersion> findLatestVersion(UUID templateId) {
        return jdbc.sql("""
                SELECT id, template_id, version, type_spec, checksum, published_at
                FROM object_template_version WHERE template_id = :templateId ORDER BY version DESC LIMIT 1
                """).param("templateId", templateId).query(this::mapVersion).optional();
    }

    public Optional<TemplateVersion> findVersionNumbered(UUID templateId, int version) {
        return jdbc.sql("""
                SELECT id, template_id, version, type_spec, checksum, published_at
                FROM object_template_version WHERE template_id = :templateId AND version = :version
                """).param("templateId", templateId).param("version", version).query(this::mapVersion).optional();
    }

    public List<TemplateVersion> findVersions(UUID templateId) {
        return jdbc.sql("""
                SELECT id, template_id, version, type_spec, checksum, published_at
                FROM object_template_version WHERE template_id = :templateId ORDER BY version DESC
                """).param("templateId", templateId).query(this::mapVersion).list();
    }

    private Template mapTemplate(ResultSet rs, int rowNum) throws SQLException {
        String draft = rs.getString("draft_spec");
        return new Template(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                draft == null ? null : json.readValue(draft, ObjectTypeSpec.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private TemplateVersion mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new TemplateVersion(rs.getObject("id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getInt("version"), json.readValue(rs.getString("type_spec"), ObjectTypeSpec.class),
                rs.getString("checksum"), rs.getObject("published_at", OffsetDateTime.class).toInstant());
    }

    private String jsonb(ObjectTypeSpec type) {
        return type == null ? null : json.writeValueAsString(type);
    }

    private String checksum(ObjectTypeSpec type) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.writeValueAsBytes(type)), 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
