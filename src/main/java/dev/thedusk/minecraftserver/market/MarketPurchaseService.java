package dev.thedusk.minecraftserver.market;

import dev.thedusk.minecraftserver.economy.EconomyException;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

public final class MarketPurchaseService {
    private final JavaPlugin plugin;
    private final PlayerIdentityRepository identities;
    private final MarketPurchaseRepository purchases;
    private final MarketListingRepository listings;
    private final PersistenceExecutor executor;
    private final MarketItemCodec codec;
    private final Logger logger;

    public MarketPurchaseService(
            JavaPlugin plugin,
            PlayerIdentityRepository identities,
            MarketPurchaseRepository purchases,
            MarketListingRepository listings,
            PersistenceExecutor executor,
            MarketItemCodec codec,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.purchases = Objects.requireNonNull(purchases, "purchases");
        this.listings = Objects.requireNonNull(listings, "listings");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void buy(Player player, long listingNumber) {
        Objects.requireNonNull(player, "player");
        if (player.isDead()) {
            player.sendMessage("§eHãy respawn trước khi mua item.");
            return;
        }
        if (listingNumber <= 0) {
            player.sendMessage("§cListing ID phải là số nguyên dương.");
            return;
        }

        UUID minecraftUuid = player.getUniqueId();
        String playerName = player.getName();
        UUID correlationId = UUID.randomUUID();
        player.sendMessage("§7Đang thanh toán listing #" + listingNumber + "...");

        executor.submit(() -> {
                    var identity = identities.recordSeen(minecraftUuid, playerName);
                    return purchases.purchase(listingNumber, identity.playerId(), correlationId);
                })
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        handlePurchaseFailure(minecraftUuid, listingNumber, failure);
                        return;
                    }
                    scheduleMain(() -> deliverPaidItem(minecraftUuid, result));
                });
    }

    public void recover(Player player) {
        Objects.requireNonNull(player, "player");
        if (player.isDead()) {
            return;
        }
        UUID minecraftUuid = player.getUniqueId();
        String playerName = player.getName();
        Map<UUID, Integer> inventoryTokens = collectTokenCounts(player.getInventory());

        executor.submit(() -> buildRecoveryPlan(minecraftUuid, playerName, inventoryTokens))
                .whenComplete((plan, failure) -> {
                    if (failure != null) {
                        logger.log(Level.WARNING, "Failed to build market purchase recovery plan for " + minecraftUuid, failure);
                        return;
                    }
                    scheduleMain(() -> applyRecoveryPlan(minecraftUuid, plan));
                });
    }

    private PurchaseRecoveryPlan buildRecoveryPlan(
            UUID minecraftUuid,
            String playerName,
            Map<UUID, Integer> inventoryTokens
    ) {
        var identity = identities.recordSeen(minecraftUuid, playerName);
        List<MarketPurchaseResult> pending = new ArrayList<>();
        for (MarketPurchase purchase : purchases.findPendingByBuyer(identity.playerId())) {
            MarketListing listing = purchases.findListing(purchase.listingId())
                    .orElseThrow(() -> new MarketException(
                            MarketException.Code.INVALID_STATE,
                            "Pending purchase is missing listing " + purchase.listingId()
                    ));
            pending.add(new MarketPurchaseResult(listing, purchase, true));
        }

        Map<UUID, MarketPurchaseResult> tokenOwnership = new LinkedHashMap<>();
        for (UUID token : inventoryTokens.keySet()) {
            listings.findByEscrowToken(token).ifPresent(listing -> {
                purchases.findByListing(listing.listingId()).ifPresent(purchase -> {
                    if (purchase.buyerPlayerId().equals(identity.playerId())) {
                        tokenOwnership.put(token, new MarketPurchaseResult(listing, purchase, true));
                    }
                });
            });
        }
        return new PurchaseRecoveryPlan(identity.playerId(), pending, tokenOwnership);
    }

    private void applyRecoveryPlan(UUID minecraftUuid, PurchaseRecoveryPlan plan) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }

        boolean changed = false;
        for (var entry : plan.tokenOwnership().entrySet()) {
            MarketPurchaseResult result = entry.getValue();
            if (result.purchase().status() == MarketPurchaseStatus.COMPLETED
                    && result.listing().status() == MarketListingStatus.COMPLETED) {
                changed |= stripToken(player.getInventory(), entry.getKey()) > 0;
            }
        }
        if (changed && !saveForStateTransition(player, "persist completed market delivery marker cleanup")) {
            player.sendMessage("§eKhông thể ghi bền vững market recovery; sẽ thử lại lần sau.");
            return;
        }

        for (MarketPurchaseResult result : plan.pending()) {
            deliverPaidItem(minecraftUuid, result);
        }
    }

    private void deliverPaidItem(UUID minecraftUuid, MarketPurchaseResult result) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        MarketListing listing = result.listing();
        MarketPurchase purchase = result.purchase();
        if (purchase.status() == MarketPurchaseStatus.COMPLETED
                && listing.status() == MarketListingStatus.COMPLETED) {
            if (stripToken(player.getInventory(), listing.escrowToken()) > 0) {
                bestEffortSave(player, "strip completed buyer delivery marker");
            }
            player.sendMessage("§7Listing #" + listing.listingNumber() + " đã được giao trước đó.");
            return;
        }
        if (purchase.status() != MarketPurchaseStatus.DELIVERY_PENDING
                || listing.status() != MarketListingStatus.DELIVERY_PENDING) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        int tokenCount = countToken(inventory, listing.escrowToken());
        if (tokenCount > 1) {
            logger.severe("Duplicate delivery token " + listing.escrowToken() + " found for buyer " + minecraftUuid);
            player.sendMessage("§cPhát hiện trạng thái delivery bất thường; item được giữ khóa để staff recovery.");
            return;
        }
        if (tokenCount == 0) {
            int emptySlot = inventory.firstEmpty();
            if (emptySlot < 0) {
                player.sendMessage("§eĐã thanh toán listing #" + listing.listingNumber()
                        + " nhưng inventory đầy. Chừa ít nhất 1 ô trống rồi dùng /market recover.");
                return;
            }
            ItemStack restored;
            try {
                byte[] payload = listing.itemPayload();
                if (!codec.sha256(payload).equals(listing.itemSha256())) {
                    throw new IllegalStateException("Market item payload hash mismatch");
                }
                restored = codec.deserialize(payload);
                UUID payloadToken = codec.escrowToken(restored)
                        .orElseThrow(() -> new IllegalStateException("Market item snapshot has no escrow token"));
                if (!payloadToken.equals(listing.escrowToken())) {
                    throw new IllegalStateException("Market item snapshot token mismatch");
                }
            } catch (RuntimeException exception) {
                logger.log(Level.SEVERE, "Cannot deserialize purchased market item " + listing.listingId(), exception);
                player.sendMessage("§cKhông thể khôi phục item đã mua tự động; thanh toán được giữ nguyên để staff recovery.");
                return;
            }
            inventory.setItem(emptySlot, restored);
        }

        // A completed database delivery is only allowed after the marked item is durable in playerdata.
        if (!saveForStateTransition(player, "persist purchased escrow item before delivery acknowledgement")) {
            player.sendMessage("§eItem đã vào inventory nhưng đang khóa tạm thời. Dùng /market recover sau.");
            return;
        }

        executor.submit(() -> purchases.completeDelivery(listing.listingId(), purchase.buyerPlayerId()))
                .whenComplete((completed, failure) -> scheduleMain(() -> {
                    Player current = plugin.getServer().getPlayer(minecraftUuid);
                    if (failure != null) {
                        logger.log(Level.WARNING, "Failed to acknowledge market delivery " + listing.listingId(), failure);
                        if (current != null && current.isOnline()) {
                            current.sendMessage("§eItem đã được lưu nhưng đang khóa tạm thời; /market recover sẽ hoàn tất.");
                        }
                        return;
                    }
                    if (current != null && current.isOnline() && !current.isDead()) {
                        if (stripToken(current.getInventory(), completed.listing().escrowToken()) > 0) {
                            bestEffortSave(current, "persist stripped buyer delivery marker");
                        }
                        current.sendMessage("§aĐã mua listing #" + completed.listing().listingNumber()
                                + " với giá " + completed.listing().priceAmount() + " "
                                + completed.listing().currencyCode() + ".");
                    }
                }));
    }

    private void handlePurchaseFailure(UUID minecraftUuid, long listingNumber, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof EconomyException economyFailure
                && economyFailure.code() == EconomyException.Code.INSUFFICIENT_FUNDS) {
            scheduleMain(() -> sendIfOnline(minecraftUuid, "§cBạn không đủ COINS để mua listing #" + listingNumber + "."));
            return;
        }
        if (cause instanceof MarketException marketFailure) {
            logger.log(Level.FINE, "Market purchase rejected #" + listingNumber, marketFailure);
            scheduleMain(() -> sendIfOnline(
                    minecraftUuid,
                    "§cKhông thể mua listing #" + listingNumber + ": " + marketFailure.getMessage()
            ));
            return;
        }
        logger.log(Level.WARNING, "Market purchase failed for listing #" + listingNumber, failure);
        scheduleMain(() -> sendIfOnline(
                minecraftUuid,
                "§eKhông xác định được trạng thái thanh toán. Dùng /market recover trước khi thử lại."
        ));
    }

    private Map<UUID, Integer> collectTokenCounts(PlayerInventory inventory) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            codec.escrowToken(inventory.getItem(slot))
                    .ifPresent(token -> counts.merge(token, 1, Integer::sum));
        }
        return Map.copyOf(counts);
    }

    private int countToken(PlayerInventory inventory, UUID token) {
        int count = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (codec.escrowToken(inventory.getItem(slot)).filter(token::equals).isPresent()) {
                count++;
            }
        }
        return count;
    }

    private int stripToken(PlayerInventory inventory, UUID token) {
        int changed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (codec.escrowToken(item).filter(token::equals).isPresent()) {
                inventory.setItem(slot, codec.strip(item));
                changed++;
            }
        }
        return changed;
    }

    private boolean saveForStateTransition(Player player, String operation) {
        try {
            player.saveData();
            return true;
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Failed to " + operation + " for " + player.getUniqueId(), exception);
            return false;
        }
    }

    private void bestEffortSave(Player player, String operation) {
        if (!saveForStateTransition(player, operation)) {
            logger.warning("Market buyer inventory state will require later recovery for " + player.getUniqueId());
        }
    }

    private void sendIfOnline(UUID minecraftUuid, String message) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player != null && player.isOnline()) {
            player.sendMessage(message);
        }
    }

    private void scheduleMain(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private record PurchaseRecoveryPlan(
            UUID buyerPlayerId,
            List<MarketPurchaseResult> pending,
            Map<UUID, MarketPurchaseResult> tokenOwnership
    ) {
        private PurchaseRecoveryPlan {
            Objects.requireNonNull(buyerPlayerId, "buyerPlayerId");
            pending = List.copyOf(pending);
            tokenOwnership = Map.copyOf(tokenOwnership);
        }
    }
}
