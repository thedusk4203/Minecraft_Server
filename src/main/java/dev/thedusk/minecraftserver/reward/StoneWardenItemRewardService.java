package dev.thedusk.minecraftserver.reward;

import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

public final class StoneWardenItemRewardService {
    private final JavaPlugin plugin;
    private final PlayerIdentityRepository identities;
    private final StoneWardenItemRewardRepository rewards;
    private final PersistenceExecutor executor;
    private final StoneWardenItemRewardCodec codec;
    private final Logger logger;
    private final Set<UUID> recoveryInFlight = ConcurrentHashMap.newKeySet();

    public StoneWardenItemRewardService(
            JavaPlugin plugin,
            PlayerIdentityRepository identities,
            StoneWardenItemRewardRepository rewards,
            PersistenceExecutor executor,
            StoneWardenItemRewardCodec codec,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.rewards = Objects.requireNonNull(rewards, "rewards");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void recover(Player player) {
        recover(player, false);
    }

    public void recover(Player player, boolean notify) {
        Objects.requireNonNull(player, "player");
        if (player.isDead() || !player.isOnline()) {
            return;
        }

        UUID minecraftUuid = player.getUniqueId();
        if (!recoveryInFlight.add(minecraftUuid)) {
            if (notify) {
                player.sendMessage("§7Stone Warden reward recovery đang chạy.");
            }
            return;
        }

        String playerName = player.getName();
        Map<UUID, Integer> inventoryTokens = collectTokenCounts(player.getInventory());
        if (notify) {
            player.sendMessage("§7Đang kiểm tra Stone Warden reward delivery...");
        }

        executor.submit(() -> buildRecoveryPlan(minecraftUuid, playerName, inventoryTokens))
                .whenComplete((plan, failure) -> {
                    if (failure != null) {
                        logger.log(Level.WARNING, "Failed to build Stone Warden item reward recovery plan for "
                                + minecraftUuid, failure);
                        scheduleMain(() -> {
                            sendIfOnline(minecraftUuid, notify,
                                    "§eKhông thể kiểm tra item reward lúc này; trạng thái bền vững được giữ nguyên.");
                            finish(minecraftUuid);
                        });
                        return;
                    }
                    scheduleMain(() -> applyRecoveryPlan(minecraftUuid, plan, notify));
                });
    }

    public void recoverOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            recover(player, false);
        }
    }

    private RecoveryPlan buildRecoveryPlan(
            UUID minecraftUuid,
            String playerName,
            Map<UUID, Integer> inventoryTokens
    ) {
        var identity = identities.recordSeen(minecraftUuid, playerName);
        rewards.prepareEligibleIntentsForPlayer(identity.playerId(), UUID.randomUUID());
        List<StoneWardenItemReward> rows = rewards.findByPlayer(identity.playerId());

        Map<UUID, StoneWardenItemReward> markerOwners = new LinkedHashMap<>();
        Set<UUID> unknownMarkers = new LinkedHashSet<>();
        for (UUID token : inventoryTokens.keySet()) {
            rewards.findByToken(token).ifPresentOrElse(
                    row -> markerOwners.put(token, row),
                    () -> unknownMarkers.add(token)
            );
        }

        return new RecoveryPlan(
                identity.playerId(),
                rows,
                markerOwners,
                unknownMarkers
        );
    }

    private void applyRecoveryPlan(UUID minecraftUuid, RecoveryPlan plan, boolean notify) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline() || player.isDead()) {
            finish(minecraftUuid);
            return;
        }

        for (UUID token : plan.unknownMarkers()) {
            deliveryConflict(minecraftUuid, token, "unknown reward delivery marker", notify);
        }
        for (var entry : plan.markerOwners().entrySet()) {
            if (!entry.getValue().playerId().equals(plan.playerId())) {
                deliveryConflict(minecraftUuid, entry.getKey(), "reward marker belongs to another player", notify);
            }
        }

        processRows(minecraftUuid, plan.rows(), 0, notify);
    }

    private void processRows(
            UUID minecraftUuid,
            List<StoneWardenItemReward> rows,
            int index,
            boolean notify
    ) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline() || player.isDead()) {
            finish(minecraftUuid);
            return;
        }
        if (index >= rows.size()) {
            finish(minecraftUuid);
            return;
        }

        StoneWardenItemReward row = rows.get(index);
        int tokenCount = countToken(player.getInventory(), row.deliveryToken());
        if (tokenCount > 1) {
            deliveryConflict(minecraftUuid, row.deliveryToken(), "duplicate reward delivery markers", notify);
            processRows(minecraftUuid, rows, index + 1, notify);
            return;
        }

        switch (row.status()) {
            case PREPARED -> prepareSnapshot(minecraftUuid, rows, index, row, tokenCount, notify);
            case DELIVERY_PENDING -> deliverPending(minecraftUuid, rows, index, row, notify);
            case DELIVERED -> {
                cleanupDeliveredMarker(player, row, notify);
                processRows(minecraftUuid, rows, index + 1, notify);
            }
        }
    }

    private void prepareSnapshot(
            UUID minecraftUuid,
            List<StoneWardenItemReward> rows,
            int index,
            StoneWardenItemReward row,
            int tokenCount,
            boolean notify
    ) {
        if (tokenCount != 0) {
            deliveryConflict(minecraftUuid, row.deliveryToken(),
                    "marker exists before durable item snapshot", notify);
            processRows(minecraftUuid, rows, index + 1, notify);
            return;
        }

        ItemStack marked = codec.createMarked(row.deliveryToken());
        byte[] payload = codec.serialize(marked);
        String sha256 = codec.sha256(payload);
        UUID correlationId = UUID.randomUUID();

        executor.submit(() -> rewards.prepareSnapshot(row.rewardId(), payload, sha256, correlationId))
                .whenComplete((prepared, failure) -> scheduleMain(() -> {
                    if (failure != null) {
                        logger.log(Level.WARNING,
                                "Failed to persist Stone Warden item snapshot " + row.rewardId(), failure);
                        sendIfOnline(minecraftUuid, notify,
                                "§eItem reward snapshot chưa thể ghi bền vững; sẽ thử lại sau.");
                        processRows(minecraftUuid, rows, index + 1, notify);
                        return;
                    }
                    logger.info("BOSS_REWARD_TELEMETRY event=item_snapshot_prepared reward="
                            + prepared.rewardId());
                    deliverPending(minecraftUuid, rows, index, prepared, notify);
                }));
    }

    private void deliverPending(
            UUID minecraftUuid,
            List<StoneWardenItemReward> rows,
            int index,
            StoneWardenItemReward row,
            boolean notify
    ) {
        Player player = plugin.getServer().getPlayer(minecraftUuid);
        if (player == null || !player.isOnline() || player.isDead()) {
            finish(minecraftUuid);
            return;
        }
        if (row.status() != StoneWardenItemRewardStatus.DELIVERY_PENDING) {
            processRows(minecraftUuid, rows, index + 1, notify);
            return;
        }

        ItemStack persistedSnapshot;
        try {
            persistedSnapshot = verifiedSnapshot(row);
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Invalid persisted Stone Warden item snapshot " + row.rewardId(), exception);
            deliveryConflict(minecraftUuid, row.deliveryToken(), "persisted item snapshot mismatch", notify);
            processRows(minecraftUuid, rows, index + 1, notify);
            return;
        }

        PlayerInventory inventory = player.getInventory();
        int tokenCount = countToken(inventory, row.deliveryToken());
        if (tokenCount > 1) {
            deliveryConflict(minecraftUuid, row.deliveryToken(), "duplicate reward delivery markers", notify);
            processRows(minecraftUuid, rows, index + 1, notify);
            return;
        }

        if (tokenCount == 1) {
            ItemStack existing = findTokenItem(inventory, row.deliveryToken());
            if (!codec.isExpectedMarkedItem(existing, row.deliveryToken())) {
                deliveryConflict(minecraftUuid, row.deliveryToken(), "existing marked item semantic mismatch", notify);
                processRows(minecraftUuid, rows, index + 1, notify);
                return;
            }
        } else {
            int emptySlot = inventory.firstEmpty();
            if (emptySlot < 0) {
                sendIfOnline(minecraftUuid, notify,
                        "§eInventory đầy. Chừa 1 ô trống rồi dùng /boss recover để nhận Stone Warden item reward.");
                processRows(minecraftUuid, rows, index + 1, notify);
                return;
            }
            inventory.setItem(emptySlot, persistedSnapshot);
        }

        if (!saveForStateTransition(player, "persist marked Stone Warden reward before delivery acknowledgement")) {
            sendIfOnline(minecraftUuid, notify,
                    "§eItem reward đang được giữ khóa; /boss recover sẽ tiếp tục khi playerdata ghi được.");
            processRows(minecraftUuid, rows, index + 1, notify);
            return;
        }

        UUID correlationId = UUID.randomUUID();
        executor.submit(() -> rewards.markDelivered(row.rewardId(), correlationId))
                .whenComplete((delivered, failure) -> scheduleMain(() -> {
                    Player current = plugin.getServer().getPlayer(minecraftUuid);
                    if (failure != null) {
                        logger.log(Level.WARNING,
                                "Failed to acknowledge Stone Warden item delivery " + row.rewardId(), failure);
                        sendIfOnline(minecraftUuid, notify,
                                "§eItem reward đã được lưu nhưng còn khóa; /boss recover sẽ hoàn tất.");
                        processRows(minecraftUuid, rows, index + 1, notify);
                        return;
                    }

                    logger.info("BOSS_REWARD_TELEMETRY event=item_delivered reward="
                            + delivered.rewardId() + " player=" + delivered.playerId());
                    if (current != null && current.isOnline() && !current.isDead()) {
                        cleanupDeliveredMarker(current, delivered, notify);
                        if (notify) {
                            current.sendMessage("§aStone Warden item reward đã được giao an toàn.");
                        }
                    }
                    processRows(minecraftUuid, rows, index + 1, notify);
                }));
    }

    private ItemStack verifiedSnapshot(StoneWardenItemReward row) {
        byte[] payload = row.itemPayload();
        if (payload == null || row.itemSha256() == null || !codec.sha256(payload).equals(row.itemSha256())) {
            throw new IllegalStateException("Stone Warden item reward payload hash mismatch");
        }
        ItemStack restored = codec.deserialize(payload);
        if (!codec.isExpectedMarkedItem(restored, row.deliveryToken())) {
            throw new IllegalStateException("Stone Warden item reward payload semantics mismatch");
        }
        return restored;
    }

    private void cleanupDeliveredMarker(Player player, StoneWardenItemReward row, boolean notify) {
        int tokenCount = countToken(player.getInventory(), row.deliveryToken());
        if (tokenCount > 1) {
            deliveryConflict(player.getUniqueId(), row.deliveryToken(),
                    "duplicate markers after durable delivery", notify);
            return;
        }
        if (tokenCount == 0) {
            return;
        }

        ItemStack existing = findTokenItem(player.getInventory(), row.deliveryToken());
        if (!codec.isExpectedMarkedItem(existing, row.deliveryToken())) {
            deliveryConflict(player.getUniqueId(), row.deliveryToken(),
                    "delivered marker semantic mismatch", notify);
            return;
        }
        if (stripToken(player.getInventory(), row.deliveryToken()) == 1) {
            bestEffortSave(player, "persist stripped Stone Warden reward marker");
            logger.info("BOSS_REWARD_TELEMETRY event=item_marker_cleaned reward=" + row.rewardId());
        }
    }

    private Map<UUID, Integer> collectTokenCounts(PlayerInventory inventory) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            codec.deliveryToken(inventory.getItem(slot))
                    .ifPresent(token -> counts.merge(token, 1, Integer::sum));
        }
        return Map.copyOf(counts);
    }

    private int countToken(PlayerInventory inventory, UUID token) {
        int count = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (codec.deliveryToken(inventory.getItem(slot)).filter(token::equals).isPresent()) {
                count++;
            }
        }
        return count;
    }

    private ItemStack findTokenItem(PlayerInventory inventory, UUID token) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (codec.deliveryToken(item).filter(token::equals).isPresent()) {
                return item;
            }
        }
        return null;
    }

    private int stripToken(PlayerInventory inventory, UUID token) {
        int changed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (codec.deliveryToken(item).filter(token::equals).isPresent()) {
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
            logger.warning("Stone Warden reward marker cleanup will require later recovery for "
                    + player.getUniqueId());
        }
    }

    private void deliveryConflict(UUID minecraftUuid, UUID token, String reason, boolean notify) {
        logger.severe("BOSS_REWARD_TELEMETRY event=item_delivery_conflict player=" + minecraftUuid
                + " token=" + token + " reason=" + reason.replace(' ', '_'));
        sendIfOnline(minecraftUuid, notify,
                "§cPhát hiện Stone Warden item reward state bất thường; item được giữ khóa để recovery.");
    }

    private void sendIfOnline(UUID minecraftUuid, boolean notify, String message) {
        if (!notify) {
            return;
        }
        Player current = plugin.getServer().getPlayer(minecraftUuid);
        if (current != null && current.isOnline()) {
            current.sendMessage(message);
        }
    }

    private void scheduleMain(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private void finish(UUID minecraftUuid) {
        recoveryInFlight.remove(minecraftUuid);
    }

    private record RecoveryPlan(
            UUID playerId,
            List<StoneWardenItemReward> rows,
            Map<UUID, StoneWardenItemReward> markerOwners,
            Set<UUID> unknownMarkers
    ) {
        private RecoveryPlan {
            Objects.requireNonNull(playerId, "playerId");
            rows = List.copyOf(new ArrayList<>(rows));
            markerOwners = Map.copyOf(markerOwners);
            unknownMarkers = Set.copyOf(unknownMarkers);
        }
    }
}
