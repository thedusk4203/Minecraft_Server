package dev.thedusk.minecraftserver.identity;

import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerIdentityListener implements Listener {
    private final PlayerIdentityRepository repository;
    private final PersistenceExecutor executor;
    private final Logger logger;

    public PlayerIdentityListener(
            PlayerIdentityRepository repository,
            PersistenceExecutor executor,
            Logger logger
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();

        executor.submit(() -> repository.recordSeen(minecraftUuid, playerName))
                .whenComplete((identity, failure) -> {
                    if (failure != null) {
                        logger.log(
                                Level.SEVERE,
                                "Failed to persist identity for Minecraft UUID " + minecraftUuid,
                                failure
                        );
                    }
                });
    }
}
