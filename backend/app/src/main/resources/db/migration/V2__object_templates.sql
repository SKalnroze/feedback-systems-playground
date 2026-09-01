-- Object type templates: a type authored once and reused across systems.
--
-- Shaped exactly like system_definition / system_version, because it is the same problem. A draft
-- is edited freely; publishing freezes a version; a system embeds a *copy* of the version it was
-- built against. That copy is what stops an edit made here from silently changing a system that was
-- validated and run months ago - the same reason a run pins a system version rather than following
-- the latest one.

CREATE TABLE object_template (
    id           UUID PRIMARY KEY,
    name         TEXT        NOT NULL,
    description  TEXT        NOT NULL DEFAULT '',
    -- The working copy the editor autosaves into, holding one ObjectTypeSpec.
    draft_spec   JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE object_template_version (
    id           UUID PRIMARY KEY,
    template_id  UUID        NOT NULL REFERENCES object_template (id) ON DELETE CASCADE,
    version      INTEGER     NOT NULL,
    type_spec    JSONB       NOT NULL,
    -- Digest of the type, so republishing an unchanged draft returns the existing version instead
    -- of creating one that differs from its predecessor in nothing but its number.
    checksum     TEXT        NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (template_id, version)
);

CREATE INDEX object_template_version_by_template ON object_template_version (template_id, version DESC);
