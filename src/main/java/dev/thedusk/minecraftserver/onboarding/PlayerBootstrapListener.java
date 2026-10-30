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
                    boolean newlyStarted = onboarding.ensureStarted(identity.playerId());
                    var progress = onboarding.find(identity.playerId()).orElseThrow(
                            () -> new IllegalStateException(
                                    "Onboarding row missing after ensureStarted for " + identity.playerId()
                            )
                    );
                    return new BootstrapResult(newlyStarted, progress);
                })
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        logger.log(
                                Level.SEVERE,
                                "Failed to bootstrap player " + minecraftUuid,
                                failure
                        );
                        return;
                    }
                    showCurrentObjective(minecraftUuid, result);
                });
    }

    private void showCurrentObjective(UUID minecraftUuid, BootstrapResult result) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            var player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) {
                return;
            }

            OnboardingProgress progress = result.progress();
            if (result.newlyStarted()) {
                player.sendMessage("§6Chào mừng! §fServer sẽ hướng dẫn từng bước ngắn thay vì một bức tường chữ.");
            }

            if (!progress.landClaimed()) {
                player.sendMessage("§6Mục tiêu hiện tại: §fbảo vệ mảnh đất của bạn.");
                player.sendMessage("§7Dùng xẻng vàng của GriefPrevention để tạo claim; tiến trình sẽ tự cập nhật khi claim thành công.");
                return;
            }

            if (!progress.firstCurrencyEarned()) {
                player.sendMessage("§6Mục tiêu hiện tại: §ftăng cấp một kỹ năng AuraSkills để nhận coin đầu tiên.");
                player.sendMessage("§7Dùng §f/skills §7để xem kỹ năng; hãy tiếp tục các hoạt động sinh tồn như đào, farm hoặc chiến đấu.");
                return;
            }

            if (!progress.marketUsed()) {
                player.sendMessage("§6Mục tiêu hiện tại: §fhoàn thành giao dịch market đầu tiên.");
                player.sendMessage("§7Dùng §f/market list §7để xem listing rồi §f/market buy <#>§7; nếu market trống, bạn có thể cầm một stack và dùng §f/market sell <giá>§7.");
                return;
            }

            player.sendMessage("§aBạn đã hoàn tất vòng onboarding hiện tại: claim đất, nhận coin và giao dịch market.");
        });
    }

    private record BootstrapResult(boolean newlyStarted, OnboardingProgress progress) {
    }
}
