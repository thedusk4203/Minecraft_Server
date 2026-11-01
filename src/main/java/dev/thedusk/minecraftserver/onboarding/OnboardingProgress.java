package dev.thedusk.minecraftserver.onboarding;

import java.time.Instant;
import java.util.UUID;

public record OnboardingProgress(
        UUID playerId,
        Instant startedAt,
        Instant landClaimedAt,
        Instant firstSkillLevelAt,
        String firstSkillId,
        Integer firstSkillLevel,
        Instant firstCurrencyEarnedAt,
        Instant marketUsedAt,
        Instant completedAt
) {
    public boolean landClaimed() {
        return landClaimedAt != null;
    }

    public boolean firstSkillLeveled() {
        return firstSkillLevelAt != null;
    }

    public boolean firstCurrencyEarned() {
        return firstCurrencyEarnedAt != null;
    }

    public boolean marketUsed() {
        return marketUsedAt != null;
    }
}
