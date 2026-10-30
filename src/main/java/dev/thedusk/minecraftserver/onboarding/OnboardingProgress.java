package dev.thedusk.minecraftserver.onboarding;

import java.time.Instant;
import java.util.UUID;

public record OnboardingProgress(
        UUID playerId,
        Instant startedAt,
        Instant landClaimedAt,
        Instant firstCurrencyEarnedAt,
        Instant marketUsedAt,
        Instant completedAt
) {
    public boolean landClaimed() {
        return landClaimedAt != null;
    }
}
