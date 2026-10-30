package dev.thedusk.minecraftserver.economy;

import java.util.UUID;

public record EconomyResult(
        UUID transactionId,
        UUID accountId,
        long delta,
        long balanceAfter,
        String reason,
        UUID correlationId,
        boolean idempotentReplay
) {
}
