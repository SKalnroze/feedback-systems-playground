-- Feedback Systems Playground: initial schema.
--
-- Two halves. Definitions are small, versioned and immutable once published, so they live as JSONB
-- documents: the shape of a system spec is the engine's business and changes with it, and mirroring
-- that shape in tables would mean a migration for every new decay model. Run data is the opposite -
-- narrow, enormous and queried by range - so it gets real columns and real indexes.

-- ---------------------------------------------------------------------------------------------
-- Definitions
-- ---------------------------------------------------------------------------------------------

CREATE TABLE system_definition (
    id           UUID PRIMARY KEY,
    name         TEXT        NOT NULL,
    description  TEXT        NOT NULL DEFAULT '',
    -- The working copy the editor autosaves into. Published versions are snapshots of this.
    draft_spec   JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE system_version (
    id            UUID PRIMARY KEY,
    system_id     UUID        NOT NULL REFERENCES system_definition (id) ON DELETE CASCADE,
    version       INTEGER     NOT NULL,
    spec          JSONB       NOT NULL,
    -- Digest of the spec, so an unchanged publish can be recognised instead of creating a version.
    checksum      TEXT        NOT NULL,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (system_id, version)
);

CREATE INDEX system_version_by_system ON system_version (system_id, version DESC);

-- ---------------------------------------------------------------------------------------------
-- Runs
-- ---------------------------------------------------------------------------------------------

CREATE TABLE simulation_run (
    id                  UUID PRIMARY KEY,
    system_version_id   UUID        NOT NULL REFERENCES system_version (id) ON DELETE CASCADE,
    name                TEXT        NOT NULL,
    seed                BIGINT      NOT NULL,
    status              TEXT        NOT NULL,
    current_tick        BIGINT      NOT NULL DEFAULT 0,
    speed_ticks_per_sec DOUBLE PRECISION NOT NULL DEFAULT 10,
    -- Set when this run was forked from a checkpoint of another run, forming a what-if tree.
    parent_run_id       UUID        REFERENCES simulation_run (id) ON DELETE SET NULL,
    forked_from_tick    BIGINT,
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT simulation_run_status CHECK (status IN ('CREATED', 'RUNNING', 'PAUSED', 'STOPPED', 'COMPLETED', 'FAILED'))
);

CREATE INDEX simulation_run_by_status ON simulation_run (status);
CREATE INDEX simulation_run_by_parent ON simulation_run (parent_run_id);

CREATE TABLE checkpoint (
    id          UUID PRIMARY KEY,
    run_id      UUID        NOT NULL REFERENCES simulation_run (id) ON DELETE CASCADE,
    tick        BIGINT      NOT NULL,
    label       TEXT,
    -- Gzipped JSON of the engine snapshot. Opaque to SQL on purpose: its shape is the engine's.
    state       BYTEA       NOT NULL,
    state_bytes INTEGER     NOT NULL,
    automatic   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX checkpoint_by_run ON checkpoint (run_id, tick DESC);

-- ---------------------------------------------------------------------------------------------
-- Observability
-- ---------------------------------------------------------------------------------------------

-- Series keys repeat on every sample, so they are interned here and referenced by a small integer.
CREATE TABLE metric_series (
    id          BIGSERIAL PRIMARY KEY,
    run_id      UUID  NOT NULL REFERENCES simulation_run (id) ON DELETE CASCADE,
    series_key  TEXT  NOT NULL,
    object_id   TEXT,
    variable    TEXT,
    category    TEXT  NOT NULL DEFAULT 'variable',
    UNIQUE (run_id, series_key)
);

CREATE INDEX metric_series_by_run ON metric_series (run_id);

-- The big one. Kept deliberately narrow; hash-partitioned so that deleting a run does not have to
-- scan every other run's samples, and so index maintenance stays local to one partition.
CREATE TABLE metric_sample (
    run_id    UUID             NOT NULL,
    series_id BIGINT           NOT NULL,
    tick      BIGINT           NOT NULL,
    value     DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (run_id, series_id, tick)
) PARTITION BY HASH (run_id);

CREATE TABLE metric_sample_p0 PARTITION OF metric_sample FOR VALUES WITH (MODULUS 8, REMAINDER 0);
CREATE TABLE metric_sample_p1 PARTITION OF metric_sample FOR VALUES WITH (MODULUS 8, REMAINDER 1);
CREATE TABLE metric_sample_p2 PARTITION OF metric_sample FOR VALUES WITH (MODULUS 8, REMAINDER 2);
CREATE TABLE metric_sample_p3 PARTITION OF metric_sample FOR VALUES WITH (MODULUS 8, REMAINDER 3);
CREATE TABLE metric_sample_p4 PARTITION OF metric_sample FOR VALUES WITH (MODULUS 8, REMAINDER 4);
CREATE TABLE metric_sample_p5 PARTITION OF metric_sample FOR VALUES WITH (MODULUS 8, REMAINDER 5);
CREATE TABLE metric_sample_p6 PARTITION OF metric_sample FOR VALUES WITH (MODULUS 8, REMAINDER 6);
CREATE TABLE metric_sample_p7 PARTITION OF metric_sample FOR VALUES WITH (MODULUS 8, REMAINDER 7);

-- Pre-aggregated buckets, so a chart spanning a hundred thousand ticks does not read a hundred
-- thousand rows per series.
CREATE TABLE metric_rollup (
    run_id      UUID             NOT NULL,
    series_id   BIGINT           NOT NULL,
    bucket_tick BIGINT           NOT NULL,
    bucket_size INTEGER          NOT NULL,
    avg_value   DOUBLE PRECISION NOT NULL,
    min_value   DOUBLE PRECISION NOT NULL,
    max_value   DOUBLE PRECISION NOT NULL,
    sample_count INTEGER         NOT NULL,
    PRIMARY KEY (run_id, series_id, bucket_size, bucket_tick)
);

-- Append-only record of everything that happened, so a chart movement can be traced to its cause.
CREATE TABLE run_event_log (
    id         BIGSERIAL PRIMARY KEY,
    run_id     UUID   NOT NULL REFERENCES simulation_run (id) ON DELETE CASCADE,
    tick       BIGINT NOT NULL,
    entry_type TEXT   NOT NULL,
    subject    TEXT,
    detail     TEXT   NOT NULL,
    payload    JSONB,
    CONSTRAINT run_event_log_type CHECK (entry_type IN ('EVENT', 'REACTIVATION', 'INTERACTION', 'CHECKPOINT', 'CONTROL', 'NOTE'))
);

CREATE INDEX run_event_log_by_run_tick ON run_event_log (run_id, tick);
CREATE INDEX run_event_log_by_type ON run_event_log (run_id, entry_type, tick);

-- Current state of every memory, for the memory inspector. Strength is stored as evaluated at
-- last_sampled_tick; the engine can always recompute it exactly from the fields here.
CREATE TABLE memory_record (
    id                 BIGINT           NOT NULL,
    run_id             UUID             NOT NULL REFERENCES simulation_run (id) ON DELETE CASCADE,
    owner_id           TEXT             NOT NULL,
    subject_id         TEXT             NOT NULL,
    kind               TEXT             NOT NULL,
    created_tick       BIGINT           NOT NULL,
    origin_tick        BIGINT           NOT NULL,
    initial_strength   DOUBLE PRECISION NOT NULL,
    current_strength   DOUBLE PRECISION NOT NULL,
    valence            DOUBLE PRECISION NOT NULL,
    salience           DOUBLE PRECISION NOT NULL,
    reactivation_count INTEGER          NOT NULL DEFAULT 0,
    last_sampled_tick  BIGINT           NOT NULL,
    forgotten_at_tick  BIGINT,
    features           JSONB,
    PRIMARY KEY (run_id, id)
);

CREATE INDEX memory_record_by_owner ON memory_record (run_id, owner_id);
CREATE INDEX memory_record_by_subject ON memory_record (run_id, subject_id);
