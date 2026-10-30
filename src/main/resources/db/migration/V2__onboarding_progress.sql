CREATE TABLE player_onboarding (
    player_id UUID PRIMARY KEY
        REFERENCES player_identity(player_id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    land_claimed_at TIMESTAMPTZ,
    first_currency_earned_at TIMESTAMPTZ,
    market_used_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_player_onboarding_completed
        CHECK (
            completed_at IS NULL
            OR (
                land_claimed_at IS NOT NULL
                AND first_currency_earned_at IS NOT NULL
                AND market_used_at IS NOT NULL
            )
        )
);

CREATE INDEX ix_player_onboarding_incomplete
    ON player_onboarding (started_at)
    WHERE completed_at IS NULL;
