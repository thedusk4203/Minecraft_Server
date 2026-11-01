package dev.thedusk.minecraftserver.market;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record MarketListing(
        UUID listingId,
        long listingNumber,
        UUID sellerPlayerId,
        MarketListingStatus status,
        UUID escrowToken,
        byte[] itemPayload,
        String itemSha256,
        String materialKey,
        int itemAmount,
        String currencyCode,
        long priceAmount,
        UUID prepareCorrelationId,
        UUID returnCorrelationId,
        Instant createdAt,
        Instant listedAt,
        Instant returnRequestedAt,
        Instant cancelledAt,
        long version
) {
    public MarketListing {
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(escrowToken, "escrowToken");
        Objects.requireNonNull(itemPayload, "itemPayload");
        Objects.requireNonNull(itemSha256, "itemSha256");
        Objects.requireNonNull(materialKey, "materialKey");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(prepareCorrelationId, "prepareCorrelationId");
        Objects.requireNonNull(createdAt, "createdAt");
        itemPayload = Arrays.copyOf(itemPayload, itemPayload.length);
    }

    @Override
    public byte[] itemPayload() {
        return Arrays.copyOf(itemPayload, itemPayload.length);
    }
}
