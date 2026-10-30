package dev.thedusk.minecraftserver.market;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MarketPurchase(
        UUID purchaseId,
        UUID listingId,
        UUID buyerPlayerId,
        MarketPurchaseStatus status,
        UUID purchaseCorrelationId,
        UUID buyerDebitTransactionId,
        UUID sellerCreditTransactionId,
        Instant purchasedAt,
        Instant completedAt,
        long version
) {
    public MarketPurchase {
        Objects.requireNonNull(purchaseId, "purchaseId");
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(buyerPlayerId, "buyerPlayerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(purchaseCorrelationId, "purchaseCorrelationId");
        Objects.requireNonNull(buyerDebitTransactionId, "buyerDebitTransactionId");
        Objects.requireNonNull(sellerCreditTransactionId, "sellerCreditTransactionId");
        Objects.requireNonNull(purchasedAt, "purchasedAt");
        if (status == MarketPurchaseStatus.COMPLETED) {
            Objects.requireNonNull(completedAt, "completedAt");
        }
    }
}
