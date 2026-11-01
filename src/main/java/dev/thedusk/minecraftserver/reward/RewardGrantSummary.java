package dev.thedusk.minecraftserver.reward;

import java.util.UUID;

public record RewardGrantSummary(
        UUID runId,
        int participantCount,
        int newlyGrantedCount,
        int replayedCount,
        String currencyCode,
        long currencyAmountEach
) {
}
