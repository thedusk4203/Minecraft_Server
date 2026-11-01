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
                    boolean firstClaim = onboarding.markLandClaimed(identity.playerId());
                    var progress = onboarding.find(identity.playerId()).orElseThrow(
                            () -> new IllegalStateException(
                                    "Onboarding row missing after land claim for " + identity.playerId()
                            )
                    );
                    return new ClaimResult(firstClaim, progress.firstCurrencyEarned());
                })
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        logger.log(
                                Level.SEVERE,
                                "Failed to record first land claim for " + minecraftUuid,
                                failure
                        );
                        return;
                    }
                    if (result.firstClaim()) {
                        showNextObjective(minecraftUuid, result.currencyAlreadyEarned());
                    }
                });
    }

    private void showNextObjective(UUID minecraftUuid, boolean currencyAlreadyEarned) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            var player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) {
                return;
            }

            player.sendMessage("§aĐã bảo vệ đất thành công.");
            if (currencyAlreadyEarned) {
                player.sendMessage("§7Bạn cũng đã có coin onboarding. Tiếp tục tích lũy tài nguyên cho bước giao dịch tiếp theo.");
            } else {
                player.sendMessage("§fBước tiếp theo: tăng cấp một kỹ năng AuraSkills để nhận coin đầu tiên.");
                player.sendMessage("§7Dùng §f/skills §7để xem các kỹ năng và nguồn XP.");
            }
        });
    }

    private record ClaimResult(boolean firstClaim, boolean currencyAlreadyEarned) {
    }
}
