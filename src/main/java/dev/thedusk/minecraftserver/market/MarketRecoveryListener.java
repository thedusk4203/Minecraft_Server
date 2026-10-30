package dev.thedusk.minecraftserver.market;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class MarketRecoveryListener implements Listener {
    private final MarketEscrowService market;

    public MarketRecoveryListener(MarketEscrowService market) {
        this.market = Objects.requireNonNull(market, "market");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        market.recover(event.getPlayer());
    }
}
