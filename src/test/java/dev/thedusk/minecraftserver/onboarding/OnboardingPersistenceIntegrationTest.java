package dev.thedusk.minecraftserver.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.economy.EconomyException;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.economy.EconomyMutation;
import dev.thedusk.minecraftserver.identity.PlayerIdentity;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.DatabaseSettings;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@Testcontainers
class OnboardingPersistenceIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_onboarding_test")
            .withUsername("minecraft_test")
            .withPassword("minecraft_test_password");

    private static DatabaseManager database;
    private static PlayerIdentityRepository identities;
    private static OnboardingProgressRepository onboarding;
    private static EconomyLedger economy;
    private static FirstSkillCurrencyService firstSkillCurrency;

    @BeforeAll
    static void openDatabase() {
        openServices();
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void clearState() throws Exception {
        try (var connection = database.connection();
             var statement = connection.createStatement()) {
            statement.execute(
                    "TRUNCATE TABLE economy_transaction, economy_account, audit_event, "
                            + "player_onboarding, player_identity CASCADE"
            );
        }
    }

    @Test
    void startingOnboardingIsIdempotent() throws Exception {
        PlayerIdentity player = identities.recordSeen(UUID.randomUUID(), "Starter");

        assertTrue(onboarding.ensureStarted(player.playerId()));
        assertFalse(onboarding.ensureStarted(player.playerId()));
        assertEquals(1L, countRows("player_onboarding"));

        OnboardingProgress progress = onboarding.find(player.playerId()).orElseThrow();
        assertNotNull(progress.startedAt());
        assertFalse(progress.landClaimed());
    }

    @Test
    void firstLandClaimIsRecordedExactlyOnce() {
        PlayerIdentity player = identities.recordSeen(UUID.randomUUID(), "Claimer");

        assertTrue(onboarding.markLandClaimed(player.playerId()));
        OnboardingProgress first = onboarding.find(player.playerId()).orElseThrow();
        assertNotNull(first.landClaimedAt());

        assertFalse(onboarding.markLandClaimed(player.playerId()));
        OnboardingProgress replay = onboarding.find(player.playerId()).orElseThrow();
        assertEquals(first.landClaimedAt(), replay.landClaimedAt());
    }

    @Test
    void firstSkillLevelAtomicallyGrantsStarterCurrencyExactlyOnce() throws Exception {
        PlayerIdentity player = identities.recordSeen(UUID.randomUUID(), "SkillStarter");
        onboarding.ensureStarted(player.playerId());
        onboarding.markLandClaimed(player.playerId());

        FirstSkillCurrencyResult first = firstSkillCurrency.recordLevelUp(
                player.playerId(),
                "auraskills/mining",
                2,
                UUID.randomUUID()
        );

        assertTrue(first.firstSkillRecorded());
        assertTrue(first.currencyGranted());
        assertEquals(FirstSkillCurrencyService.STARTER_REWARD, first.balanceAfter());
        assertTrue(first.landClaimed());

        OnboardingProgress progress = onboarding.find(player.playerId()).orElseThrow();
        assertTrue(progress.firstSkillLeveled());
        assertEquals("auraskills/mining", progress.firstSkillId());
        assertEquals(2, progress.firstSkillLevel());
        assertTrue(progress.firstCurrencyEarned());
        assertEquals(FirstSkillCurrencyService.STARTER_REWARD, economy.balance(player.playerId(), "COINS"));
        assertEquals(1L, countRows("economy_transaction"));
        assertEquals(1L, countRows("audit_event"));

        FirstSkillCurrencyResult replay = firstSkillCurrency.recordLevelUp(
                player.playerId(),
                "auraskills/farming",
                3,
                UUID.randomUUID()
        );

        assertFalse(replay.firstSkillRecorded());
        assertFalse(replay.currencyGranted());
        assertEquals(FirstSkillCurrencyService.STARTER_REWARD, economy.balance(player.playerId(), "COINS"));
        assertEquals(1L, countRows("economy_transaction"));
        assertEquals(1L, countRows("audit_event"));

        OnboardingProgress afterReplay = onboarding.find(player.playerId()).orElseThrow();
        assertEquals("auraskills/mining", afterReplay.firstSkillId());
        assertEquals(2, afterReplay.firstSkillLevel());
    }

    @Test
    void economyConflictRollsBackSkillAndCurrencyMilestones() {
        PlayerIdentity player = identities.recordSeen(UUID.randomUUID(), "AtomicRollback");
        onboarding.ensureStarted(player.playerId());
        String onboardingKey = "onboarding:first-currency:" + player.playerId();

        economy.apply(new EconomyMutation(
                player.playerId(),
                "COINS",
                1L,
                "test.conflict",
                onboardingKey,
                UUID.randomUUID(),
                "{}"
        ));

        EconomyException exception = assertThrows(
                EconomyException.class,
                () -> firstSkillCurrency.recordLevelUp(
                        player.playerId(),
                        "auraskills/farming",
                        2,
                        UUID.randomUUID()
                )
        );

        assertEquals(EconomyException.Code.IDEMPOTENCY_CONFLICT, exception.code());
        OnboardingProgress progress = onboarding.find(player.playerId()).orElseThrow();
        assertFalse(progress.firstSkillLeveled());
        assertFalse(progress.firstCurrencyEarned());
        assertNull(progress.firstSkillId());
        assertNull(progress.firstSkillLevel());
        assertEquals(1L, economy.balance(player.playerId(), "COINS"));
    }

    @Test
    void restartPreservesLandSkillAndCurrencyMilestones() {
        UUID minecraftUuid = UUID.randomUUID();
        PlayerIdentity beforeRestart = identities.recordSeen(minecraftUuid, "RestartSkill");
        onboarding.markLandClaimed(beforeRestart.playerId());
        firstSkillCurrency.recordLevelUp(
                beforeRestart.playerId(),
                "auraskills/foraging",
                2,
                UUID.randomUUID()
        );

        database.close();
        openServices();

        PlayerIdentity afterRestart = identities.findByMinecraftUuid(minecraftUuid).orElseThrow();
        OnboardingProgress progress = onboarding.find(afterRestart.playerId()).orElseThrow();
        assertEquals(beforeRestart.playerId(), afterRestart.playerId());
        assertTrue(progress.landClaimed());
        assertTrue(progress.firstSkillLeveled());
        assertEquals("auraskills/foraging", progress.firstSkillId());
        assertEquals(2, progress.firstSkillLevel());
        assertTrue(progress.firstCurrencyEarned());
        assertEquals(FirstSkillCurrencyService.STARTER_REWARD, economy.balance(afterRestart.playerId(), "COINS"));
    }

    private static void openServices() {
        database = DatabaseManager.open(new DatabaseSettings(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                4
        ));
        identities = new PlayerIdentityRepository(database);
        onboarding = new OnboardingProgressRepository(database);
        economy = new EconomyLedger(database, new AuditLog());
        firstSkillCurrency = new FirstSkillCurrencyService(database, onboarding, economy);
    }

    private static long countRows(String table) throws Exception {
        try (var connection = database.connection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }
}
