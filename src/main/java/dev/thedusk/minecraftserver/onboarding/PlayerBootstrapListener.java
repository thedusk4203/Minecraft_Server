package dev.thedusk.minecraftserver.onboarding;

import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerBootstrapListener implements Listener {
    private final JavaPlugin plugin;
    private final PlayerIdentityRepository identities;
    private final OnboardingProgressRepository onboarding;
    private final PersistenceExecutor executor;
    private final Logger logger;

    public PlayerBootstrapListener(
            JavaPlugin plugin,
            PlayerIdentityRepository identities,
            OnboardingProgressRepository onboarding,
            PersistenceExecutor executor,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();

        executor.submit(() -> {
                    var identity = identities.recordSeen(minecraftUuid, playerName);
                    return onboarding.ensureStarted(identity.playerId());
                })
                .whenComplete((newlyStarted, failure) -> {
                    if (failure != null) {
                        logger.log(
                                Level.SEVERE,
                                "Failed to bootstrap player " + minecraftUuid,
                                failure
                        );
                        return;
                    }
                    if (Boolean.TRUE.equals(newlyStarted)) {
                        showFirstObjective(minecraftUuid);
                    }
                });
    }

    private void showFirstObjective(UUID minecraftUuid) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            var player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            player.sendMessage("§6Chào mừng! §fMục tiêu đầu tiên: bảo vệ mảnh đất của bạn.");
            player.sendMessage("§7Dùng xẻng vàng của GriefPrevention để tạo claim; tiến trình sẽ tự cập nhật khi claim thành công.");
        });
    }
}
