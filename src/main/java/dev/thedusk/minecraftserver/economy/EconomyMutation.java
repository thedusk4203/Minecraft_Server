package dev.thedusk.minecraftserver.economy;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record EconomyMutation(
        UUID playerId,
        String currencyCode,
        long delta,
        String reason,
        String idempotencyKey,
        UUID correlationId,
        String metadataJson
) {
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("[A-Z0-9_]{1,32}");

    public EconomyMutation {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(correlationId, "correlationId");

        currencyCode = normalizeCurrencyCode(currencyCode);
        reason = requireLength(reason, "reason", 96);
        idempotencyKey = requireLength(idempotencyKey, "idempotencyKey", 128);
        metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;

        if (delta == 0) {
            throw new IllegalArgumentException("delta must not be zero");
        }
    }

    public static String normalizeCurrencyCode(String value) {
        Objects.requireNonNull(value, "currencyCode");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("currencyCode must match [A-Z0-9_]{1,32}");
        }
        return normalized;
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
