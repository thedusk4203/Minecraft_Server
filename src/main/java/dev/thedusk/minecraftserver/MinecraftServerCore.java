package dev.thedusk.minecraftserver;

import dev.thedusk.minecraftserver.admin.AdminCommandHandler;
import dev.thedusk.minecraftserver.admin.AdminDiagnosticsService;
import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.market.MarketCommandHandler;
import dev.thedusk.minecraftserver.market.MarketEscrowGuardListener;
import dev.thedusk.minecraftserver.market.MarketEscrowService;
import dev.thedusk.minecraftserver.market.MarketItemCodec;
import dev.thedusk.minecraftserver.market.MarketListingRepository;
import dev.thedusk.minecraftserver.market.MarketRecoveryListener;
import dev.thedusk.minecraftserver.onboarding.AuraSkillsOnboardingListener;
import dev.thedusk.minecraftserver.onboarding.FirstSkillCurrencyService;
import dev.thedusk.minecraftserver.onboarding.LandClaimOnboardingListener;
import dev.thedusk.minecraftserver.onboarding.OnboardingProgressRepository;
import dev.thedusk.minecraftserver.onboarding.PlayerBootstrapListener;
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
    private OnboardingProgressRepository onboarding;
    private EconomyLedger economy;
    private MarketEscrowService market;

    @Override
    public void onEnable() {
        try {
            database = DatabaseManager.open(DatabaseSettings.fromEnvironment(System.getenv()));
            persistenceExecutor = new PersistenceExecutor();
            identities = new PlayerIdentityRepository(database);
            onboarding = new OnboardingProgressRepository(database);

            AuditLog auditLog = new AuditLog();
            economy = new EconomyLedger(database, auditLog);
            FirstSkillCurrencyService firstSkillCurrency = new FirstSkillCurrencyService(
                    database,
                    onboarding,
                    economy
            );
            AdminDiagnosticsService diagnostics = new AdminDiagnosticsService(database, auditLog);

            MarketListingRepository marketListings = new MarketListingRepository(database, auditLog);
            MarketItemCodec marketItemCodec = new MarketItemCodec(this);
            market = new MarketEscrowService(
                    this,
                    identities,
                    marketListings,
                    persistenceExecutor,
                    marketItemCodec,
                    getLogger()
            );

            getServer().getPluginManager().registerEvents(
                    new PlayerBootstrapListener(
                            this,
                            identities,
                            onboarding,
                            persistenceExecutor,
                            getLogger()
                    ),
                    this
            );
            getServer().getPluginManager().registerEvents(
                    new LandClaimOnboardingListener(
                            this,
                            identities,
                            onboarding,
                            persistenceExecutor,
                            getLogger()
                    ),
                    this
            );
            getServer().getPluginManager().registerEvents(
                    new AuraSkillsOnboardingListener(
                            this,
                            identities,
                            firstSkillCurrency,
                            persistenceExecutor,
                            getLogger()
                    ),
                    this
            );
            getServer().getPluginManager().registerEvents(
                    new MarketEscrowGuardListener(marketItemCodec),
                    this
            );
            getServer().getPluginManager().registerEvents(
                    new MarketRecoveryListener(this, market),
                    this
            );

            configureAdminCommand(diagnostics);
            configureMarketCommand();

            getLogger().info(
                    "MinecraftServerCore enabled on Minecraft "
                            + getServer().getMinecraftVersion()
                            + " with PostgreSQL persistence, onboarding, land protection, AuraSkills and market escrow"
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

    private void configureMarketCommand() {
        PluginCommand command = Objects.requireNonNull(
                getCommand("market"),
                "market command must be declared in plugin.yml"
        );
        command.setExecutor(new MarketCommandHandler(Objects.requireNonNull(market, "market")));
    }

    @Override
    public void onDisable() {
        closeResources();
        getLogger().info("MinecraftServerCore disabled");
    }

    private void closeResources() {
        market = null;

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
        onboarding = null;
        economy = null;
    }
}
