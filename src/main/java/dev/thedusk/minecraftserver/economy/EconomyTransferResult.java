package dev.thedusk.minecraftserver.economy;

import java.util.UUID;

public record EconomyTransferResult(
        UUID debitTransactionId,
        UUID creditTransactionId,
        UUID fromPlayerId,
        UUID toPlayerId,
        String currencyCode,
        long amount,
        long fromBalanceAfter,
        long toBalanceAfter,
        UUID correlationId,
        boolean idempotentReplay
) {
}
