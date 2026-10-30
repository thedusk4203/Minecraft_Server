ALTER TABLE player_onboarding
    ADD COLUMN first_skill_level_at TIMESTAMPTZ,
    ADD COLUMN first_skill_id VARCHAR(128),
    ADD COLUMN first_skill_level INTEGER;

ALTER TABLE player_onboarding
    ADD CONSTRAINT ck_player_onboarding_first_skill
    CHECK (
        (first_skill_level_at IS NULL AND first_skill_id IS NULL AND first_skill_level IS NULL)
        OR (
            first_skill_level_at IS NOT NULL
            AND first_skill_id IS NOT NULL
            AND first_skill_level IS NOT NULL
            AND first_skill_level >= 1
        )
    );
