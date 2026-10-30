package dev.thedusk.minecraftserver;

import dev.thedusk.minecraftserver.admin.AdminCommandHandler;
import dev.thedusk.minecraftserver.admin.AdminDiagnosticsService;
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

            AuditLog auditLog = new AuditLog();
            economy = new EconomyLedger(database, auditLog);
            AdminDiagnosticsService diagnostics = new AdminDiagnosticsService(database, auditLog);

            getServer().getPluginManager().registerEvents(
                    new PlayerIdentityListener(identities, persistenceExecutor, getLogger()),
                    this
            );
            configureAdminCommand(diagnostics);

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

    private void configureAdminCommand(AdminDiagnosticsService diagnostics) {
        PluginCommand command = Objects.requireNonNull(
                getCommand("mcserver"),
                "mcserver command must be declared in plugin.yml"
        );

        command.setExecutor(new AdminCommandHandler(
                this,
                database,
                persistenceExecutor,
                diagnostics
        ));
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
