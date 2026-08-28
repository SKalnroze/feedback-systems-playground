package dev.fsp.app.persistence;

import dev.fsp.app.persistence.SystemRecords.SystemDefinition;
import dev.fsp.app.persistence.SystemRecords.SystemVersion;
import dev.fsp.engine.spec.SystemSpec;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Reads and writes system definitions and their published versions. */
@Repository
public class SystemRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public SystemRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID create(String name, String description, SystemSpec draft) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO system_definition (id, name, description, draft_spec)
                VALUES (:id, :name, :description, CAST(:draft AS jsonb))
                """).param("id", id).param("name", name).param("description", description)
                .param("draft", jsonb(draft)).update();
        return id;
    }

    public List<SystemDefinition> findAll() {
        return jdbc.sql("""
                SELECT id, name, description, draft_spec, created_at, updated_at
                FROM system_definition ORDER BY updated_at DESC
                """).query(this::mapDefinition).list();
    }

    public Optional<SystemDefinition> find(UUID id) {
        return jdbc.sql("""
                SELECT id, name, description, draft_spec, created_at, updated_at
                FROM system_definition WHERE id = :id
                """).param("id", id).query(this::mapDefinition).optional();
    }

    /** Saves the editor's working copy. Publishing is a separate, explicit step. */
    public void saveDraft(UUID id, String name, String description, SystemSpec draft) {
        jdbc.sql("""
                UPDATE system_definition
                SET name = :name, description = :description, draft_spec = CAST(:draft AS jsonb), updated_at = now()
                WHERE id = :id
                """).param("id", id).param("name", name).param("description", description)
                .param("draft", jsonb(draft)).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM system_definition WHERE id = :id").param("id", id).update();
    }

    /**
     * Publishes a version, unless an identical one is already the latest.
     *
     * <p>Republishing an unchanged spec returns the existing version rather than creating a
     * duplicate: the editor autosaves, and a version list full of identical entries would make the
     * history useless for the thing it is for, which is telling runs apart.
     */
    public SystemVersion publish(UUID systemId, SystemSpec spec) {
        String checksum = checksum(spec);
        Optional<SystemVersion> latest = findLatestVersion(systemId);
        if (latest.isPresent() && latest.get().checksum().equals(checksum)) {
            return latest.get();
        }

        int nextVersion = latest.map(SystemVersion::version).orElse(0) + 1;
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO system_version (id, system_id, version, spec, checksum)
                VALUES (:id, :systemId, :version, CAST(:spec AS jsonb), :checksum)
                """).param("id", id).param("systemId", systemId).param("version", nextVersion)
                .param("spec", jsonb(spec)).param("checksum", checksum).update();

        return findVersion(id).orElseThrow(() -> new IllegalStateException("version vanished after insert"));
    }

    public Optional<SystemVersion> findVersion(UUID versionId) {
        return jdbc.sql("""
                SELECT id, system_id, version, spec, checksum, published_at
                FROM system_version WHERE id = :id
                """).param("id", versionId).query(this::mapVersion).optional();
    }

    public Optional<SystemVersion> findLatestVersion(UUID systemId) {
        return jdbc.sql("""
                SELECT id, system_id, version, spec, checksum, published_at
                FROM system_version WHERE system_id = :systemId ORDER BY version DESC LIMIT 1
                """).param("systemId", systemId).query(this::mapVersion).optional();
    }

    public List<SystemVersion> findVersions(UUID systemId) {
        return jdbc.sql("""
                SELECT id, system_id, version, spec, checksum, published_at
                FROM system_version WHERE system_id = :systemId ORDER BY version DESC
                """).param("systemId", systemId).query(this::mapVersion).list();
    }

    private SystemDefinition mapDefinition(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String draft = rs.getString("draft_spec");
        return new SystemDefinition(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                draft == null ? null : json.readValue(draft, SystemSpec.class),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", java.time.OffsetDateTime.class).toInstant());
    }

    private SystemVersion mapVersion(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SystemVersion(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                rs.getInt("version"), json.readValue(rs.getString("spec"), SystemSpec.class),
                rs.getString("checksum"), rs.getObject("published_at", java.time.OffsetDateTime.class).toInstant());
    }

    /**
     * Specs travel to Postgres as text and are cast to jsonb in the statement, which keeps the
     * driver a runtime-only dependency instead of leaking PGobject into application code.
     */
    private String jsonb(SystemSpec spec) {
        return spec == null ? null : json.writeValueAsString(spec);
    }

    /** Content hash of a spec, used to recognise a republish of something unchanged. */
    private String checksum(SystemSpec spec) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.writeValueAsBytes(spec));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
