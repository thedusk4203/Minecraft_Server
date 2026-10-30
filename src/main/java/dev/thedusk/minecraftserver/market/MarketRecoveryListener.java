package dev.thedusk.minecraftserver.market;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class MarketRecoveryListener implements Listener {
    private final JavaPlugin plugin;
    private final MarketEscrowService market;

    public MarketRecoveryListener(JavaPlugin plugin, MarketEscrowService market) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.market = Objects.requireNonNull(market, "market");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        market.recover(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> market.recover(event.getPlayer()));
    }
}
