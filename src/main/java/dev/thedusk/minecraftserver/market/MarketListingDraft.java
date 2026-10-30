package dev.thedusk.minecraftserver.market;

import dev.thedusk.minecraftserver.economy.EconomyMutation;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record MarketListingDraft(
        UUID sellerPlayerId,
        UUID escrowToken,
        byte[] itemPayload,
        String itemSha256,
        String materialKey,
        int itemAmount,
        String currencyCode,
        long priceAmount,
        UUID correlationId
) {
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MATERIAL_KEY_PATTERN = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    public MarketListingDraft {
        Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");
        Objects.requireNonNull(escrowToken, "escrowToken");
        Objects.requireNonNull(itemPayload, "itemPayload");
        Objects.requireNonNull(correlationId, "correlationId");

        if (itemPayload.length == 0) {
            throw new IllegalArgumentException("itemPayload must not be empty");
        }
        if (itemPayload.length > 1_048_576) {
            throw new IllegalArgumentException("itemPayload must be at most 1 MiB");
        }
        itemPayload = Arrays.copyOf(itemPayload, itemPayload.length);

        itemSha256 = require(itemSha256, "itemSha256", 64).toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(itemSha256).matches()) {
            throw new IllegalArgumentException("itemSha256 must be 64 lowercase hex characters");
        }
        materialKey = require(materialKey, "materialKey", 128).toLowerCase(Locale.ROOT);
        if (!MATERIAL_KEY_PATTERN.matcher(materialKey).matches()) {
            throw new IllegalArgumentException("materialKey must be a lowercase namespaced key");
        }
        if (itemAmount <= 0) {
            throw new IllegalArgumentException("itemAmount must be positive");
        }
        currencyCode = EconomyMutation.normalizeCurrencyCode(currencyCode);
        if (priceAmount <= 0) {
            throw new IllegalArgumentException("priceAmount must be positive");
        }
    }

    @Override
    public byte[] itemPayload() {
        return Arrays.copyOf(itemPayload, itemPayload.length);
    }

    private static String require(String value, String field, int maximum) {
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
