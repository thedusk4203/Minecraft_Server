package dev.thedusk.minecraftserver.economy;

import java.util.Objects;
import java.util.UUID;

public record EconomyTransfer(
        UUID fromPlayerId,
        UUID toPlayerId,
        String currencyCode,
        long amount,
        String reason,
        String idempotencyKey,
        UUID correlationId,
        String metadataJson
) {
    public EconomyTransfer {
        Objects.requireNonNull(fromPlayerId, "fromPlayerId");
        Objects.requireNonNull(toPlayerId, "toPlayerId");
        Objects.requireNonNull(correlationId, "correlationId");

        if (fromPlayerId.equals(toPlayerId)) {
            throw new IllegalArgumentException("fromPlayerId and toPlayerId must be different");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }

        currencyCode = EconomyMutation.normalizeCurrencyCode(currencyCode);
        reason = requireLength(reason, "reason", 96);
        idempotencyKey = requireLength(idempotencyKey, "idempotencyKey", 120);
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
    }

    public String debitIdempotencyKey() {
        return idempotencyKey + ":debit";
    }

    public String creditIdempotencyKey() {
        return idempotencyKey + ":credit";
    }

    private static String requireLength(String value, String field, int maximum) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " must be at most " + maximum + " characters");
        }
        return normalized;
    }
}
