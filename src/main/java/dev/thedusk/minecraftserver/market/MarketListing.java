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
        UUID buyerPlayerId,
        UUID purchaseCorrelationId,
        UUID buyerDebitTransactionId,
        UUID sellerCreditTransactionId,
        Instant createdAt,
        Instant listedAt,
        Instant returnRequestedAt,
        Instant cancelledAt,
        Instant purchasedAt,
        Instant completedAt,
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

        if (status == MarketListingStatus.DELIVERY_PENDING || status == MarketListingStatus.COMPLETED) {
            Objects.requireNonNull(buyerPlayerId, "buyerPlayerId");
            Objects.requireNonNull(purchaseCorrelationId, "purchaseCorrelationId");
            Objects.requireNonNull(buyerDebitTransactionId, "buyerDebitTransactionId");
            Objects.requireNonNull(sellerCreditTransactionId, "sellerCreditTransactionId");
            Objects.requireNonNull(purchasedAt, "purchasedAt");
        }
        if (status == MarketListingStatus.COMPLETED) {
            Objects.requireNonNull(completedAt, "completedAt");
        }
    }

    @Override
    public byte[] itemPayload() {
        return Arrays.copyOf(itemPayload, itemPayload.length);
    }
}
