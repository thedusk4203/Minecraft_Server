CREATE TABLE encounter_reward_item (
    reward_id UUID PRIMARY KEY REFERENCES encounter_reward(reward_id) ON DELETE RESTRICT,
    item_key VARCHAR(96) NOT NULL,
    item_version INTEGER NOT NULL,
    delivery_token UUID NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    item_material VARCHAR(96) NOT NULL,
    item_amount INTEGER NOT NULL,
    item_payload BYTEA,
    item_sha256 CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    snapshot_prepared_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_encounter_reward_item_key_nonempty CHECK (length(item_key) > 0),
    CONSTRAINT ck_encounter_reward_item_version_positive CHECK (item_version > 0),
    CONSTRAINT ck_encounter_reward_item_token_identity CHECK (delivery_token = reward_id),
    CONSTRAINT ck_encounter_reward_item_status
        CHECK (status IN ('PREPARED', 'DELIVERY_PENDING', 'DELIVERED')),
    CONSTRAINT ck_encounter_reward_item_material_nonempty CHECK (length(item_material) > 0),
    CONSTRAINT ck_encounter_reward_item_amount_positive CHECK (item_amount > 0),
    CONSTRAINT ck_encounter_reward_item_version_nonnegative CHECK (version >= 0),
    CONSTRAINT ck_encounter_reward_item_sha256
        CHECK (item_sha256 IS NULL OR item_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_encounter_reward_item_lifecycle CHECK (
        (status = 'PREPARED'
            AND item_payload IS NULL
            AND item_sha256 IS NULL
            AND snapshot_prepared_at IS NULL
            AND delivered_at IS NULL)
        OR
        (status = 'DELIVERY_PENDING'
            AND item_payload IS NOT NULL
            AND octet_length(item_payload) > 0
            AND item_sha256 IS NOT NULL
            AND snapshot_prepared_at IS NOT NULL
            AND delivered_at IS NULL)
        OR
        (status = 'DELIVERED'
            AND item_payload IS NOT NULL
            AND octet_length(item_payload) > 0
            AND item_sha256 IS NOT NULL
            AND snapshot_prepared_at IS NOT NULL
            AND delivered_at IS NOT NULL)
    )
);

CREATE INDEX ix_encounter_reward_item_status
    ON encounter_reward_item (status, updated_at);
