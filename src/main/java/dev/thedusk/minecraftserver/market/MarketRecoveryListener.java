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
    private final MarketEscrowService escrow;
    private final MarketPurchaseService purchases;

    public MarketRecoveryListener(
            JavaPlugin plugin,
            MarketEscrowService escrow,
            MarketPurchaseService purchases
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.escrow = Objects.requireNonNull(escrow, "escrow");
        this.purchases = Objects.requireNonNull(purchases, "purchases");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        escrow.recover(event.getPlayer());
        purchases.recover(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            escrow.recover(event.getPlayer());
            purchases.recover(event.getPlayer());
        });
    }
}
