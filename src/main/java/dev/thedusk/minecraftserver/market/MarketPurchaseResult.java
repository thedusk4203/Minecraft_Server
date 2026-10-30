package dev.thedusk.minecraftserver.market;

import java.util.Objects;

public record MarketPurchaseResult(
        MarketListing listing,
        MarketPurchase purchase,
        boolean idempotentReplay
) {
    public MarketPurchaseResult {
        Objects.requireNonNull(listing, "listing");
        Objects.requireNonNull(purchase, "purchase");
    }
}
