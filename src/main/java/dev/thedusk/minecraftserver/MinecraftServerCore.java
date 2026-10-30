package dev.thedusk.minecraftserver;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.identity.PlayerIdentityListener;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.DatabaseSettings;
import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MinecraftServerCore extends JavaPlugin {
    private DatabaseManager database;
    private PersistenceExecutor persistenceExecutor;
    private PlayerIdentityRepository identities;
    private EconomyLedger economy;

    @Override
    public void onEnable() {
        try {
            database = DatabaseManager.open(DatabaseSettings.fromEnvironment(System.getenv()));
            persistenceExecutor = new PersistenceExecutor();
            identities = new PlayerIdentityRepository(database);
            economy = new EconomyLedger(database, new AuditLog());

            getServer().getPluginManager().registerEvents(
                    new PlayerIdentityListener(identities, persistenceExecutor, getLogger()),
                    this
            );
            configureAdminCommand();

            getLogger().info(
                    "MinecraftServerCore enabled on Minecraft "
                            + getServer().getMinecraftVersion()
                            + " with PostgreSQL persistence"
            );
        } catch (RuntimeException exception) {
            closeResources();
            getLogger().log(Level.SEVERE, "MinecraftServerCore failed to initialize; failing closed", exception);
            throw exception;
        }
    }

    private void configureAdminCommand() {
        PluginCommand command = Objects.requireNonNull(
                getCommand("mcserver"),
                "mcserver command must be declared in plugin.yml"
        );

        command.setExecutor((sender, ignoredCommand, ignoredLabel, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("health")) {
                DatabaseManager activeDatabase = database;
                boolean databaseHealthy = activeDatabase != null && activeDatabase.isHealthy();
                String pool = activeDatabase == null
                        ? "closed"
                        : activeDatabase.activeConnections() + "/" + activeDatabase.idleConnections();

                sender.sendMessage(
                        "MinecraftServerCore OK | Minecraft "
                                + getServer().getMinecraftVersion()
                                + " | players="
                                + getServer().getOnlinePlayers().size()
                                + " | db="
                                + (databaseHealthy ? "UP" : "DOWN")
                                + " | pool(active/idle)="
                                + pool
                );
                return true;
            }

            sender.sendMessage("Usage: /mcserver health");
            return true;
        });
    }

    @Override
    public void onDisable() {
        closeResources();
        getLogger().info("MinecraftServerCore disabled");
    }

    private void closeResources() {
        PersistenceExecutor activeExecutor = persistenceExecutor;
        persistenceExecutor = null;
        if (activeExecutor != null) {
            activeExecutor.close();
        }

        DatabaseManager activeDatabase = database;
        database = null;
        if (activeDatabase != null) {
            activeDatabase.close();
        }

        identities = null;
        economy = null;
    }
}
