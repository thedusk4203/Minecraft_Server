package dev.thedusk.minecraftserver;

import dev.thedusk.minecraftserver.activity.DungeonCommandHandler;
import dev.thedusk.minecraftserver.activity.StoneVaultActivityManager;
import dev.thedusk.minecraftserver.activity.StructuredActivityDefinitionRegistry;
import dev.thedusk.minecraftserver.activity.StructuredActivityRunRepository;
import dev.thedusk.minecraftserver.admin.AdminCommandHandler;
import dev.thedusk.minecraftserver.admin.AdminDiagnosticsService;
import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.boss.BossCommandHandler;
import dev.thedusk.minecraftserver.boss.StoneWardenBossManager;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.encounter.EncounterDefinitionRegistry;
import dev.thedusk.minecraftserver.encounter.EncounterParticipantReservationRegistry;
import dev.thedusk.minecraftserver.encounter.EncounterRunRepository;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.market.MarketCommandHandler;
import dev.thedusk.minecraftserver.market.MarketEscrowGuardListener;
import dev.thedusk.minecraftserver.market.MarketEscrowService;
import dev.thedusk.minecraftserver.market.MarketItemCodec;
import dev.thedusk.minecraftserver.market.MarketListingRepository;
import dev.thedusk.minecraftserver.market.MarketPurchaseRepository;
import dev.thedusk.minecraftserver.market.MarketPurchaseService;
import dev.thedusk.minecraftserver.market.MarketRecoveryListener;
import dev.thedusk.minecraftserver.onboarding.AuraSkillsOnboardingListener;
import dev.thedusk.minecraftserver.onboarding.FirstSkillCurrencyService;
import dev.thedusk.minecraftserver.onboarding.LandClaimOnboardingListener;
import dev.thedusk.minecraftserver.onboarding.OnboardingProgressRepository;
import dev.thedusk.minecraftserver.onboarding.PlayerBootstrapListener;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.DatabaseSettings;
import dev.thedusk.minecraftserver.persistence.PersistenceExecutor;
import dev.thedusk.minecraftserver.reward.StoneWardenItemRewardCodec;
import dev.thedusk.minecraftserver.reward.StoneWardenItemRewardGuardListener;
import dev.thedusk.minecraftserver.reward.StoneWardenItemRewardRecoveryListener;
import dev.thedusk.minecraftserver.reward.StoneWardenItemRewardRepository;
import dev.thedusk.minecraftserver.reward.StoneWardenItemRewardService;
import dev.thedusk.minecraftserver.reward.StoneWardenRewardService;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class MinecraftServerCore extends JavaPlugin {
    private DatabaseManager database;
    private PersistenceExecutor persistenceExecutor;
    private PlayerIdentityRepository identities;
    private OnboardingProgressRepository onboarding;
    private EconomyLedger economy;
    private MarketEscrowService marketEscrow;
    private MarketPurchaseService marketPurchases;
    private EncounterDefinitionRegistry encounterDefinitions;
    private EncounterRunRepository encounterRuns;
    private StructuredActivityDefinitionRegistry activityDefinitions;
    private StructuredActivityRunRepository activityRuns;
    private EncounterParticipantReservationRegistry encounterReservations;
    private StoneWardenRewardService bossRewards;
    private StoneWardenItemRewardRepository bossItemRewardRepository;
    private StoneWardenItemRewardService bossItemRewards;
    private StoneWardenBossManager bossManager;
    private StoneVaultActivityManager stoneVaultManager;
    private BukkitTask bossRewardReconcileTask;
    private final AtomicBoolean bossRewardReconcileInFlight = new AtomicBoolean();

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

            encounterDefinitions = EncounterDefinitionRegistry.load(getClass().getClassLoader());
            encounterRuns = new EncounterRunRepository(database, auditLog);
            activityDefinitions = StructuredActivityDefinitionRegistry.load(getClass().getClassLoader());
            activityRuns = new StructuredActivityRunRepository(database, auditLog);
            encounterReservations = new EncounterParticipantReservationRegistry();

            UUID encounterRecoveryCorrelationId = UUID.randomUUID();
            int recoveredEncounterRuns = encounterRuns.recoverInterruptedRuns(
                    encounterRecoveryCorrelationId
            );
            bossRewards = new StoneWardenRewardService(database, auditLog, economy);
            int reconciledBossRewardRuns = bossRewards.reconcileCompletedPending();
            bossItemRewardRepository = new StoneWardenItemRewardRepository(database, auditLog);
            int reconciledBossItemIntents = bossItemRewardRepository.prepareEligibleIntents(UUID.randomUUID());
            StoneWardenItemRewardCodec bossItemRewardCodec = new StoneWardenItemRewardCodec(this);
            bossItemRewards = new StoneWardenItemRewardService(
                    this,
                    identities,
                    bossItemRewardRepository,
                    persistenceExecutor,
                    bossItemRewardCodec,
                    getLogger()
            );
            bossManager = new StoneWardenBossManager(
                    this,
                    identities,
                    encounterDefinitions,
                    encounterRuns,
                    encounterReservations,
                    persistenceExecutor,
                    getLogger()
            );
            stoneVaultManager = new StoneVaultActivityManager(
                    this,
                    identities,
                    activityDefinitions,
                    activityRuns,
                    encounterDefinitions,
                    encounterRuns,
                    encounterReservations,
                    bossManager,
                    persistenceExecutor,
                    getLogger()
            );

            AdminDiagnosticsService diagnostics = new AdminDiagnosticsService(database, auditLog);

            MarketListingRepository marketListings = new MarketListingRepository(database, auditLog);
            MarketItemCodec marketItemCodec = new MarketItemCodec(this);
            marketEscrow = new MarketEscrowService(
                    this,
                    identities,
                    marketListings,
                    persistenceExecutor,
                    marketItemCodec,
                    getLogger()
            );
            MarketPurchaseRepository marketPurchaseRepository = new MarketPurchaseRepository(
                    database,
                    auditLog,
                    economy,
                    onboarding
            );
            marketPurchases = new MarketPurchaseService(
                    this,
                    identities,
                    marketPurchaseRepository,
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
                    new MarketRecoveryListener(this, marketEscrow, marketPurchases),
                    this
            );
            getServer().getPluginManager().registerEvents(
                    new StoneWardenItemRewardGuardListener(bossItemRewardCodec),
                    this
            );
            getServer().getPluginManager().registerEvents(
                    new StoneWardenItemRewardRecoveryListener(this, bossItemRewards),
                    this
            );
            getServer().getPluginManager().registerEvents(bossManager, this);
            getServer().getPluginManager().registerEvents(stoneVaultManager, this);

            configureAdminCommand(diagnostics);
            configureMarketCommand();
            configureBossCommand();
            configureDungeonCommand();
            startBossRewardReconciler();

            getLogger().info(
                    "MinecraftServerCore enabled on Minecraft "
                            + getServer().getMinecraftVersion()
                            + " with PostgreSQL persistence, onboarding, land protection, AuraSkills, durable market"
                            + " and " + encounterDefinitions.size() + " validated encounter definition(s)"
                            + "; " + activityDefinitions.size() + " validated structured activity definition(s)"
                            + "; Stone Warden combat runtime=READY"
                            + "; Stone Vault runtime=READY"
                            + "; recoveredEncounterRuns=" + recoveredEncounterRuns
                            + "; recoveryCorrelation=" + encounterRecoveryCorrelationId
                            + "; reconciledBossRewardRuns=" + reconciledBossRewardRuns
                            + "; reconciledBossItemIntents=" + reconciledBossItemIntents
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
        command.setExecutor(new MarketCommandHandler(
                Objects.requireNonNull(marketEscrow, "marketEscrow"),
                Objects.requireNonNull(marketPurchases, "marketPurchases")
        ));
    }

    private void configureBossCommand() {
        PluginCommand command = Objects.requireNonNull(
                getCommand("boss"),
                "boss command must be declared in plugin.yml"
        );
        command.setExecutor(new BossCommandHandler(
                Objects.requireNonNull(bossManager, "bossManager"),
                Objects.requireNonNull(bossItemRewards, "bossItemRewards")
        ));
    }

    private void configureDungeonCommand() {
        PluginCommand command = Objects.requireNonNull(
                getCommand("dungeon"),
                "dungeon command must be declared in plugin.yml"
        );
        command.setExecutor(new DungeonCommandHandler(
                Objects.requireNonNull(stoneVaultManager, "stoneVaultManager")
        ));
    }

    private void startBossRewardReconciler() {
        bossRewardReconcileTask = getServer().getScheduler().runTaskTimer(
                this,
                () -> {
                    StoneWardenRewardService currencyRewards = bossRewards;
                    StoneWardenItemRewardRepository itemRewards = bossItemRewardRepository;
                    PersistenceExecutor executor = persistenceExecutor;
                    if (currencyRewards == null || itemRewards == null || executor == null
                            || !bossRewardReconcileInFlight.compareAndSet(false, true)) {
                        return;
                    }
                    executor.submit(() -> {
                                int runs = currencyRewards.reconcileCompletedPending();
                                int itemIntents = itemRewards.prepareEligibleIntents(UUID.randomUUID());
                                return new RewardReconcileResult(runs, itemIntents);
                            })
                            .whenComplete((result, failure) -> {
                                bossRewardReconcileInFlight.set(false);
                                if (failure != null) {
                                    getLogger().log(
                                            Level.SEVERE,
                                            "Failed to reconcile Stone Warden rewards",
                                            failure
                                    );
                                    return;
                                }
                                if (result != null && (result.runs() > 0 || result.itemIntents() > 0)) {
                                    getLogger().info(
                                            "BOSS_REWARD_TELEMETRY event=reconciled runs=" + result.runs()
                                                    + " itemIntents=" + result.itemIntents()
                                    );
                                }
                                StoneWardenItemRewardService delivery = bossItemRewards;
                                if (delivery != null && isEnabled()) {
                                    getServer().getScheduler().runTask(this, delivery::recoverOnlinePlayers);
                                }
                            });
                },
                20L,
                100L
        );
    }

    @Override
    public void onDisable() {
        closeResources();
        getLogger().info("MinecraftServerCore disabled");
    }

    private void closeResources() {
        BukkitTask activeRewardTask = bossRewardReconcileTask;
        bossRewardReconcileTask = null;
        if (activeRewardTask != null) {
            activeRewardTask.cancel();
        }
        bossRewardReconcileInFlight.set(false);

        StoneVaultActivityManager activeStoneVaultManager = stoneVaultManager;
        stoneVaultManager = null;
        if (activeStoneVaultManager != null) {
            activeStoneVaultManager.close();
        }

        StoneWardenBossManager activeBossManager = bossManager;
        bossManager = null;
        if (activeBossManager != null) {
            activeBossManager.close();
        }

        bossItemRewards = null;
        bossItemRewardRepository = null;
        bossRewards = null;
        activityRuns = null;
        activityDefinitions = null;
        encounterReservations = null;
        encounterRuns = null;
        encounterDefinitions = null;
        marketPurchases = null;
        marketEscrow = null;

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

    private record RewardReconcileResult(int runs, int itemIntents) {
    }
}
