CREATE TABLE encounter_run (
    run_id UUID PRIMARY KEY,
    encounter_id VARCHAR(96) NOT NULL,
    encounter_version INTEGER NOT NULL,
    definition_sha256 CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    start_correlation_id UUID NOT NULL UNIQUE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at TIMESTAMPTZ,
    terminal_correlation_id UUID,
    terminal_reason VARCHAR(160),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT encounter_run_id_chk
        CHECK (encounter_id ~ '^[a-z0-9][a-z0-9_.-]{0,95}$'),
    CONSTRAINT encounter_run_definition_version_chk
        CHECK (encounter_version > 0),
    CONSTRAINT encounter_run_definition_hash_chk
        CHECK (definition_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT encounter_run_status_chk
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'FAILED', 'ABANDONED')),
    CONSTRAINT encounter_run_lifecycle_chk
        CHECK (
            (status = 'ACTIVE'
                AND ended_at IS NULL
                AND terminal_correlation_id IS NULL)
            OR
            (status <> 'ACTIVE'
                AND ended_at IS NOT NULL
                AND terminal_correlation_id IS NOT NULL)
        ),
    CONSTRAINT encounter_run_version_chk
        CHECK (version >= 0)
);

CREATE INDEX encounter_run_status_started_idx
    ON encounter_run (status, started_at, run_id);

CREATE INDEX encounter_run_encounter_started_idx
    ON encounter_run (encounter_id, encounter_version, started_at DESC);

CREATE TABLE encounter_participant (
    run_id UUID NOT NULL REFERENCES encounter_run(run_id) ON DELETE CASCADE,
    player_id UUID NOT NULL REFERENCES player_identity(player_id) ON DELETE RESTRICT,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (run_id, player_id)
);

CREATE INDEX encounter_participant_player_idx
    ON encounter_participant (player_id, joined_at DESC);
