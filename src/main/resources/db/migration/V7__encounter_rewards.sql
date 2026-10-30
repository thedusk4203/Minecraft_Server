CREATE TABLE encounter_reward (
    reward_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES encounter_run(run_id) ON DELETE RESTRICT,
    player_id UUID NOT NULL REFERENCES player_identity(player_id) ON DELETE RESTRICT,
    reward_key VARCHAR(96) NOT NULL,
    reward_version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    currency_code VARCHAR(16) NOT NULL,
    currency_amount BIGINT NOT NULL,
    currency_transaction_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT encounter_reward_identity_uq
        UNIQUE (run_id, player_id, reward_key, reward_version),
    CONSTRAINT encounter_reward_key_chk
        CHECK (reward_key ~ '^[a-z0-9][a-z0-9_.-]{0,95}$'),
    CONSTRAINT encounter_reward_definition_version_chk
        CHECK (reward_version > 0),
    CONSTRAINT encounter_reward_status_chk
        CHECK (status IN ('PENDING', 'CURRENCY_GRANTED')),
    CONSTRAINT encounter_reward_currency_code_chk
        CHECK (currency_code ~ '^[A-Z0-9_]{1,16}$'),
    CONSTRAINT encounter_reward_currency_amount_chk
        CHECK (currency_amount > 0),
    CONSTRAINT encounter_reward_lifecycle_chk
        CHECK (
            (status = 'PENDING' AND currency_transaction_id IS NULL)
            OR
            (status = 'CURRENCY_GRANTED' AND currency_transaction_id IS NOT NULL)
        ),
    CONSTRAINT encounter_reward_version_chk
        CHECK (version >= 0)
);

CREATE INDEX encounter_reward_status_created_idx
    ON encounter_reward (status, created_at, reward_id);

CREATE INDEX encounter_reward_player_created_idx
    ON encounter_reward (player_id, created_at DESC);

CREATE INDEX encounter_reward_run_idx
    ON encounter_reward (run_id, player_id);
