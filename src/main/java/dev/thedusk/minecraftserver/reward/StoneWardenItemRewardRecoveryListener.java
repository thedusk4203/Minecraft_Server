package dev.thedusk.minecraftserver.reward;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class StoneWardenItemRewardRecoveryListener implements Listener {
    private final JavaPlugin plugin;
    private final StoneWardenItemRewardService rewards;

    public StoneWardenItemRewardRecoveryListener(
            JavaPlugin plugin,
            StoneWardenItemRewardService rewards
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.rewards = Objects.requireNonNull(rewards, "rewards");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        rewards.recover(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> rewards.recover(event.getPlayer()));
    }
}
