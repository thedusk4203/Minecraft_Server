package dev.thedusk.minecraftserver.onboarding;

import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.ryanhamshire.GriefPrevention.events.ClaimCreatedEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class LandClaimOnboardingListener implements Listener {
    private final JavaPlugin plugin;
    private final PlayerIdentityRepository identities;
    private final OnboardingProgressRepository onboarding;
    private final PersistenceExecutor executor;
    private final Logger logger;

    public LandClaimOnboardingListener(
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClaimCreated(ClaimCreatedEvent event) {
        if (!(event.getCreator() instanceof Player player)) {
            return;
        }

        UUID minecraftUuid = player.getUniqueId();
        String playerName = player.getName();

        executor.submit(() -> {
                    var identity = identities.recordSeen(minecraftUuid, playerName);
                    return onboarding.markLandClaimed(identity.playerId());
                })
                .whenComplete((firstClaim, failure) -> {
                    if (failure != null) {
                        logger.log(
                                Level.SEVERE,
                                "Failed to record first land claim for " + minecraftUuid,
                                failure
                        );
                        return;
                    }
                    if (Boolean.TRUE.equals(firstClaim)) {
                        showNextObjective(minecraftUuid);
                    }
                });
    }

    private void showNextObjective(UUID minecraftUuid) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            var player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            player.sendMessage("§aĐã bảo vệ đất thành công. §fBước tiếp theo: kiếm đồng coin đầu tiên.");
        });
    }
}
