CREATE TABLE structured_activity_run (
    run_id UUID PRIMARY KEY REFERENCES encounter_run(run_id) ON DELETE CASCADE,
    activity_id VARCHAR(96) NOT NULL,
    activity_version INTEGER NOT NULL,
    definition_sha256 CHAR(64) NOT NULL,
    current_beat SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT structured_activity_run_id_chk
        CHECK (activity_id ~ '^[a-z0-9][a-z0-9_.-]{0,95}$'),
    CONSTRAINT structured_activity_run_version_chk
        CHECK (activity_version > 0),
    CONSTRAINT structured_activity_run_definition_hash_chk
        CHECK (definition_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT structured_activity_run_beat_chk
        CHECK (current_beat BETWEEN 1 AND 3),
    CONSTRAINT structured_activity_run_row_version_chk
        CHECK (version >= 0)
);

CREATE INDEX structured_activity_run_activity_beat_idx
    ON structured_activity_run (activity_id, activity_version, current_beat, updated_at);
