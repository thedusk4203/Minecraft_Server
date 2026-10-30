package dev.thedusk.minecraftserver.market;

import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

public final class MarketEscrowService {
    private static final String CURRENCY = "COINS";

    private final JavaPlugin plugin;
    private final PlayerIdentityRepository identities;
    private final MarketListingRepository listings;
    private final PersistenceExecutor executor;
    private final MarketItemCodec codec;
    private final Logger logger;
    private final Set<UUID> inFlightPreparationTokens = ConcurrentHashMap.newKeySet();

    public MarketEscrowService(
            JavaPlugin plugin,
            PlayerIdentityRepository identities,
            MarketListingRepository listings,
            PersistenceExecutor executor,
            MarketItemCodec codec,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.listings = Objects.requireNonNull(listings, "listings");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void sell(Player player, long priceAmount) {
        Objects.requireNonNull(player, "player");
        if (player.isDead()) {
            player.sendMessage("§eHãy respawn trước khi tạo listing.");
            return;
        }
        if (priceAmount <= 0) {
            player.sendMessage("§cGiá bán phải lớn hơn 0 COINS.");
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR || held.getAmount() <= 0) {
            player.sendMessage("§cHãy cầm vật phẩm muốn bán ở tay chính.");
            return;
        }
        if (codec.isMarked(held)) {
            player.sendMessage("§eVật phẩm này đang ở trong một thao tác escrow chưa hoàn tất.");
            return;
        }

        UUID minecraftUuid = player.getUniqueId();
        String playerName = player.getName();
        UUID escrowToken = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        ItemStack marked = codec.mark(held, escrowToken);
        player.getInventory().setItemInMainHand(marked);

        byte[] payload;
        String sha256;
        try {
            payload = codec.serialize(marked);
            sha256 = codec.sha256(payload);
            // PREPARED may only become durable after the escrow marker itself is durable in playerdata.
            player.saveData();
        } catch (RuntimeException exception) {
            player.getInventory().setItemInMainHand(codec.strip(marked));
            bestEffortSave(player, "rollback escrow marker after serialization/save failure");
            logger.log(Level.SEVERE, "Failed to durably mark market item for " + minecraftUuid, exception);
            player.sendMessage("§cKhông thể chuẩn bị vật phẩm để bán.");
            return;
        }

        String materialKey = marked.getType().getKey().toString();
        int amount = marked.getAmount();
        player.sendMessage("§7Đang đưa vật phẩm vào escrow...");
        inFlightPreparationTokens.add(escrowToken);

        executor.submit(() -> {
                    var identity = identities.recordSeen(minecraftUuid, playerName);
                    return listings.prepare(new MarketListingDraft(
                            identity.playerId(),
                            escrowToken,
                            payload,
                            sha256,
                            materialKey,
                            amount,
                            CURRENCY,
                            priceAmount,
                            correlationId
                    ));
                })
                .whenComplete((listing, failure) -> {
                    inFlightPreparationTokens.remove(escrowToken);
                    if (failure != null) {
                        logger.log(Level.WARNING, "Market listing preparation needs reconciliation for " + minecraftUuid, failure);
                        reconcilePreparationFailure(minecraftUuid, escrowToken);
                        return;
                    }
                    scheduleMain(() -> finalizePreparedEscrow(minecraftUuid, listing));
                });
    }

    public void list(Player player) {
        Objects.requireNonNull(player, "player");
        UUID minecraftUuid = player.getUniqueId();
        executor.submit(() -> listings.listActive(20))
                .whenComplete((active, failure) -> scheduleMain(() -> {
                    Player current = plugin.getServer().getPlayer(minecraftUuid);
                    if (current == null || !current.isOnline()) {
                        return;
                    }
                    if (failure != null) {
                        logger.log(Level.WARNING, "Failed to list market entries", failure);
                        current.sendMessage("§cKhông thể đọc market lúc này.");
                        return;
                    }
                    if (active.isEmpty()) {
                        current.sendMessage("§7Market chưa có listing nào.");
                        return;
                    }
                    current.sendMessage("§6Market §7(tối đa 20 listing đang mở):");
                    for (MarketListing listing : active) {
                        current.sendMessage("§e#" + listing.listingNumber()
                                + " §f" + listing.itemAmount() + "x " + listing.materialKey()
                                + " §7- §a" + listing.priceAmount() + " " + listing.currencyCode());
                    }
                    current.sendMessage("§7Mua item sẽ được mở ở Phase 3C2b sau khi delivery recovery hoàn tất.");
                }));
    }

    public void cancel(Player player, long listingNumber) {
        Objects.requireNonNull(player, "player");
        UUID minecraftUuid = player.getUniqueId();
        String playerName = player.getName();
        UUID correlationId = UUID.randomUUID();

        executor.submit(() -> {
                    var identity = identities.recordSeen(minecraftUuid, playerName);
                    return listings.requestReturn(listingNumber, identity.playerId(), correlationId);
                })
                .whenComplete((listing, failure) -> {
                    if (failure != null) {
                        logger.log(Level.WARNING, "Failed to request market listing cancellation #" + listingNumber, failure);
                        reconcileCancellationRequest(minecraftUuid, playerName, listingNumber);
                        return;
                    }
                    scheduleMain(() -> deliverReturn(minecraftUuid, listing));
                });
    }

    public void recover(Player player) {
        Objects.requireNonNull(player, "player");
        if (player.isDead()) {
            return;
        }
        UUID minecraftUuid = player.getUniqueId();
        String playerName = player.getName();
        Set<UUID> inventoryTokens = collectMarkedTokens(player.getInventory());
        Set<UUID> preparationsAtStart = Set.copyOf(inFlightPreparationTokens);

        executor.submit(() -> buildRecoveryPlan(
                        minecraftUuid,
                        playerName,
                        inventoryTokens,
                        preparationsAtStart
                ))
                .whenComplete((plan, failure) -> {
                    if (failure != null) {
                        logger.log(Level.WARNING, "Failed to build market recovery plan for " + minecraftUuid, failure);
                        return;
                    }
                    scheduleMain(() -> applyRecoveryPlan(minecraftUuid, plan));
                });
    }

    private void reconcilePreparationFailure(UUID minecraftUuid, UUID escrowToken) {
        executor.submit(() -> listings.findByEscrowToken(escrowToken))
                .whenComplete((existing, lookupFailure) -> {
                    if (lookupFailure != null) {
                        logger.log(Level.WARNING, "Could not reconcile market preparation token " + escrowToken, lookupFailure);
                        scheduleMain(() -> sendIfOnline(
                                minecraftUuid,
                                "§eEscrow đang chờ khôi phục. Vật phẩm được khóa tạm thời; dùng /market recover sau."
                        ));
                        return;
                    }
                    if (existing.isPresent()) {
                        scheduleMain(() -> finalizePreparedEscrow(minecraftUuid, existing.orElseThrow()));
                    } else {
                        scheduleMain(() -> {
                            Player player = plugin.getServer().getPlayer(minecraftUuid);
                            if (player != null && player.isOnline() && !player.isDead()) {
                                if (stripToken(player.getInventory(), escrowToken) > 0) {
                                    bestEffortSave(player, "unlock failed market preparation");
                                }
                                player.sendMessage("§cKhông tạo được listing; vật phẩm đã được mở khóa.");
                            }
                        });
                    }
                });
    }

    private void finalizePreparedEscrow(UUID minecraftUuid, MarketListing listing) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        if (listing.status() == MarketListingStatus.LISTED) {
            if (removeToken(player.getInventory(), listing.escrowToken()) > 0) {
                bestEffortSave(player, "remove duplicate item for listed escrow");
            }
            player.sendMessage("§aListing #" + listing.listingNumber() + " đã sẵn sàng trên market.");
            return;
        }
        if (listing.status() != MarketListingStatus.PREPARED) {
            return;
        }

        removeToken(player.getInventory(), listing.escrowToken());
        if (!saveForStateTransition(player, "persist escrow removal before listing activation")) {
            player.sendMessage("§eKhông thể ghi bền vững inventory; listing vẫn ở PREPARED. Dùng /market recover sau.");
            return;
        }

        executor.submit(() -> listings.activatePrepared(listing.listingId(), listing.sellerPlayerId()))
                .whenComplete((activated, failure) -> scheduleMain(() -> {
                    Player current = plugin.getServer().getPlayer(minecraftUuid);
                    if (failure != null) {
                        logger.log(Level.WARNING, "Failed to activate prepared market listing " + listing.listingId(), failure);
                        if (current != null && current.isOnline()) {
                            current.sendMessage("§eItem đã vào escrow nhưng listing đang chờ khôi phục. Dùng /market recover.");
                        }
                        return;
                    }
                    if (current != null && current.isOnline()) {
                        current.sendMessage("§aĐã tạo listing #" + activated.listingNumber()
                                + " với giá " + activated.priceAmount() + " " + activated.currencyCode() + ".");
                    }
                }));
    }

    private void reconcileCancellationRequest(UUID minecraftUuid, String playerName, long listingNumber) {
        executor.submit(() -> {
                    var identity = identities.recordSeen(minecraftUuid, playerName);
                    return new CancellationReconciliation(
                            identity.playerId(),
                            listings.findByNumber(listingNumber).orElse(null)
                    );
                })
                .whenComplete((reconciliation, lookupFailure) -> {
                    if (lookupFailure != null) {
                        logger.log(Level.WARNING, "Could not reconcile cancellation request #" + listingNumber, lookupFailure);
                        scheduleMain(() -> sendIfOnline(minecraftUuid, "§eKhông xác định được trạng thái cancel; dùng /market recover."));
                        return;
                    }
                    MarketListing listing = reconciliation.listing();
                    if (listing == null) {
                        scheduleMain(() -> sendIfOnline(minecraftUuid, "§cKhông tìm thấy listing #" + listingNumber + "."));
                        return;
                    }
                    if (!listing.sellerPlayerId().equals(reconciliation.requestingPlayerId())) {
                        scheduleMain(() -> sendIfOnline(minecraftUuid, "§cListing #" + listingNumber + " không thuộc về bạn."));
                        return;
                    }
                    if (listing.status() == MarketListingStatus.RETURN_PENDING
                            || listing.status() == MarketListingStatus.CANCELLED) {
                        scheduleMain(() -> deliverReturn(minecraftUuid, listing));
                    } else {
                        scheduleMain(() -> sendIfOnline(minecraftUuid, "§cKhông thể cancel listing #" + listingNumber + "."));
                    }
                });
    }

    private void deliverReturn(UUID minecraftUuid, MarketListing listing) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        if (listing.status() == MarketListingStatus.CANCELLED) {
            if (stripToken(player.getInventory(), listing.escrowToken()) > 0) {
                bestEffortSave(player, "strip recovered cancelled-listing marker");
            }
            player.sendMessage("§7Listing #" + listing.listingNumber() + " đã được hủy.");
            return;
        }
        if (listing.status() != MarketListingStatus.RETURN_PENDING) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        if (!containsToken(inventory, listing.escrowToken())) {
            int emptySlot = inventory.firstEmpty();
            if (emptySlot < 0) {
                player.sendMessage("§eListing #" + listing.listingNumber()
                        + " đang chờ trả item. Hãy chừa ít nhất 1 ô trống rồi dùng /market recover.");
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
                logger.log(Level.SEVERE, "Cannot restore market listing item " + listing.listingId(), exception);
                player.sendMessage("§cKhông thể khôi phục item tự động; listing được giữ nguyên để staff recovery.");
                return;
            }
            inventory.setItem(emptySlot, restored);
        }

        // A terminal DB cancellation is only allowed after the returned marked item is durable in playerdata.
        if (!saveForStateTransition(player, "persist returned escrow item before cancellation acknowledgement")) {
            player.sendMessage("§eKhông thể ghi bền vững item đã trả; listing vẫn RETURN_PENDING. Dùng /market recover sau.");
            return;
        }

        executor.submit(() -> listings.completeCancel(listing.listingId(), listing.sellerPlayerId()))
                .whenComplete((cancelled, failure) -> scheduleMain(() -> {
                    Player current = plugin.getServer().getPlayer(minecraftUuid);
                    if (failure != null) {
                        logger.log(Level.WARNING, "Failed to acknowledge returned market item " + listing.listingId(), failure);
                        if (current != null && current.isOnline()) {
                            current.sendMessage("§eItem đã được trả nhưng đang khóa tạm thời; /market recover sẽ hoàn tất.");
                        }
                        return;
                    }
                    if (current != null && current.isOnline() && !current.isDead()) {
                        if (stripToken(current.getInventory(), cancelled.escrowToken()) > 0) {
                            bestEffortSave(current, "persist stripped marker after cancellation acknowledgement");
                        }
                        current.sendMessage("§aĐã hủy listing #" + cancelled.listingNumber() + " và trả item.");
                    }
                }));
    }

    private RecoveryPlan buildRecoveryPlan(
            UUID minecraftUuid,
            String playerName,
            Set<UUID> inventoryTokens,
            Set<UUID> preparationsAtStart
    ) {
        var identity = identities.recordSeen(minecraftUuid, playerName);
        List<MarketListing> open = listings.findOpenBySeller(identity.playerId());
        var byToken = new java.util.HashMap<UUID, MarketListing>();
        for (MarketListing listing : open) {
            byToken.put(listing.escrowToken(), listing);
        }
        for (UUID token : inventoryTokens) {
            if (!byToken.containsKey(token)) {
                listings.findByEscrowToken(token).ifPresent(listing -> byToken.put(token, listing));
            }
        }
        return new RecoveryPlan(identity.playerId(), open, byToken, preparationsAtStart);
    }

    private void applyRecoveryPlan(UUID minecraftUuid, RecoveryPlan plan) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        Set<UUID> inventoryTokens = collectMarkedTokens(inventory);
        boolean changed = false;

        for (UUID token : inventoryTokens) {
            MarketListing listing = plan.byToken().get(token);
            if (listing == null) {
                if (!plan.deferredPreparationTokens().contains(token)) {
                    changed |= stripToken(inventory, token) > 0;
                }
                continue;
            }
            switch (listing.status()) {
                case PREPARED, RETURN_PENDING -> {
                    // PREPARED is finalized below; RETURN_PENDING is delivered/acknowledged below.
                }
                case LISTED -> changed |= removeToken(inventory, token) > 0;
                case CANCELLED -> changed |= stripToken(inventory, token) > 0;
            }
        }

        if (changed && !saveForStateTransition(player, "persist market recovery inventory cleanup")) {
            player.sendMessage("§eKhông thể ghi bền vững market recovery; sẽ thử lại lần sau.");
            return;
        }

        for (MarketListing listing : plan.openListings()) {
            switch (listing.status()) {
                case PREPARED -> finalizePreparedEscrow(minecraftUuid, listing);
                case RETURN_PENDING -> deliverReturn(minecraftUuid, listing);
                case LISTED, CANCELLED -> {
                    // No additional transition required.
                }
            }
        }
    }

    private Set<UUID> collectMarkedTokens(PlayerInventory inventory) {
        Set<UUID> tokens = new LinkedHashSet<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            codec.escrowToken(inventory.getItem(slot)).ifPresent(tokens::add);
        }
        return Set.copyOf(tokens);
    }

    private boolean containsToken(PlayerInventory inventory, UUID token) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (codec.escrowToken(inventory.getItem(slot)).filter(token::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private int removeToken(PlayerInventory inventory, UUID token) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (codec.escrowToken(item).filter(token::equals).isPresent()) {
                inventory.setItem(slot, null);
                removed++;
            }
        }
        return removed;
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
            logger.warning("Market inventory state will require later recovery for " + player.getUniqueId());
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

    private record CancellationReconciliation(UUID requestingPlayerId, MarketListing listing) {
        private CancellationReconciliation {
            Objects.requireNonNull(requestingPlayerId, "requestingPlayerId");
        }
    }

    private record RecoveryPlan(
            UUID sellerPlayerId,
            List<MarketListing> openListings,
            Map<UUID, MarketListing> byToken,
            Set<UUID> deferredPreparationTokens
    ) {
        private RecoveryPlan {
            Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");
            openListings = List.copyOf(openListings);
            byToken = Map.copyOf(byToken);
            deferredPreparationTokens = Set.copyOf(deferredPreparationTokens);
        }
    }
}
