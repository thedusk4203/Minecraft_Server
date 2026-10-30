CREATE TABLE player_identity (
    player_id UUID PRIMARY KEY,
    minecraft_uuid UUID NOT NULL UNIQUE,
    last_known_name VARCHAR(16) NOT NULL,
    platform VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    platform_subject VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_player_identity_platform
        CHECK (platform IN ('JAVA', 'BEDROCK', 'UNKNOWN'))
);

CREATE INDEX ix_player_identity_last_seen
    ON player_identity (last_seen_at DESC);

CREATE TABLE audit_event (
    event_id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_player_id UUID REFERENCES player_identity(player_id) ON DELETE SET NULL,
    event_type VARCHAR(96) NOT NULL,
    correlation_id UUID NOT NULL,
    idempotency_key VARCHAR(160),
    payload JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT uq_audit_event_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_audit_event_type_nonempty CHECK (length(event_type) > 0)
);

CREATE INDEX ix_audit_event_occurred_at
    ON audit_event (occurred_at DESC);
CREATE INDEX ix_audit_event_actor
    ON audit_event (actor_player_id, occurred_at DESC);
CREATE INDEX ix_audit_event_correlation
    ON audit_event (correlation_id);

CREATE TABLE economy_account (
    account_id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES player_identity(player_id) ON DELETE RESTRICT,
    currency_code VARCHAR(32) NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_economy_account_player_currency UNIQUE (player_id, currency_code),
    CONSTRAINT ck_economy_account_balance_nonnegative CHECK (balance >= 0),
    CONSTRAINT ck_economy_account_version_nonnegative CHECK (version >= 0),
    CONSTRAINT ck_economy_account_currency_nonempty CHECK (length(currency_code) > 0)
);

CREATE TABLE economy_transaction (
    transaction_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES economy_account(account_id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    delta BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    reason VARCHAR(96) NOT NULL,
    correlation_id UUID NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_economy_transaction_delta_nonzero CHECK (delta <> 0),
    CONSTRAINT ck_economy_transaction_balance_nonnegative CHECK (balance_after >= 0),
    CONSTRAINT ck_economy_transaction_reason_nonempty CHECK (length(reason) > 0)
);

CREATE INDEX ix_economy_transaction_account_time
    ON economy_transaction (account_id, created_at DESC);
CREATE INDEX ix_economy_transaction_correlation
    ON economy_transaction (correlation_id);
