package dev.thedusk.minecraftserver.onboarding;

import dev.aurelium.auraskills.api.event.skill.SkillLevelUpEvent;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class AuraSkillsOnboardingListener implements Listener {
    private final JavaPlugin plugin;
    private final PlayerIdentityRepository identities;
    private final FirstSkillCurrencyService firstSkillCurrency;
    private final PersistenceExecutor executor;
    private final Logger logger;

    public AuraSkillsOnboardingListener(
            JavaPlugin plugin,
            PlayerIdentityRepository identities,
            FirstSkillCurrencyService firstSkillCurrency,
            PersistenceExecutor executor,
            Logger logger
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.firstSkillCurrency = Objects.requireNonNull(firstSkillCurrency, "firstSkillCurrency");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSkillLevelUp(SkillLevelUpEvent event) {
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();
        String skillId = event.getSkill().getId().toString();
        int level = event.getLevel();
        UUID correlationId = UUID.randomUUID();

        executor.submit(() -> {
                    var identity = identities.recordSeen(minecraftUuid, playerName);
                    return firstSkillCurrency.recordLevelUp(
                            identity.playerId(),
                            skillId,
                            level,
                            correlationId
                    );
                })
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        logger.log(
                                Level.SEVERE,
                                "Failed to record AuraSkills onboarding progress for " + minecraftUuid
                                        + "; correlation=" + correlationId,
                                failure
                        );
                        return;
                    }
                    if (result.firstSkillRecorded() || result.currencyGranted()) {
                        showProgress(minecraftUuid, result);
                    }
                });
    }

    private void showProgress(UUID minecraftUuid, FirstSkillCurrencyResult result) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            var player = plugin.getServer().getPlayer(minecraftUuid);
            if (player == null || !player.isOnline()) {
                return;
            }

            if (result.currencyGranted()) {
                player.sendMessage(
                        "§aKỹ năng " + result.skillId() + " đạt cấp " + result.skillLevel()
                                + ". §fBạn nhận §6" + FirstSkillCurrencyService.STARTER_REWARD
                                + " coin §fkhởi đầu."
                );
            } else if (result.firstSkillRecorded()) {
                player.sendMessage(
                        "§aĐã ghi nhận kỹ năng đầu tiên: §f" + result.skillId()
                                + " cấp " + result.skillLevel() + "."
                );
            }

            if (!result.landClaimed()) {
                player.sendMessage("§7Mục tiêu hiện tại vẫn là tạo một claim bảo vệ đất bằng xẻng vàng.");
            } else {
                player.sendMessage("§7Bước tiếp theo: dùng §f/market list §7và hoàn thành giao dịch market đầu tiên.");
            }
        });
    }
}
