package dev.thedusk.minecraftserver.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.DatabaseSettings;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class FirstSkillCurrencyConcurrencyIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_skill_concurrency_test")
            .withUsername("minecraft_test")
            .withPassword("minecraft_test_password");

    private static DatabaseManager database;
    private static PlayerIdentityRepository identities;
    private static OnboardingProgressRepository onboarding;
    private static EconomyLedger economy;
    private static FirstSkillCurrencyService firstSkillCurrency;

    @BeforeAll
    static void openDatabase() {
        database = DatabaseManager.open(new DatabaseSettings(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                6
        ));
        identities = new PlayerIdentityRepository(database);
        onboarding = new OnboardingProgressRepository(database);
        economy = new EconomyLedger(database, new AuditLog());
        firstSkillCurrency = new FirstSkillCurrencyService(database, onboarding, economy);
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
    void concurrentFirstSkillEventsSerializeToOneRewardAndOneFirstSkill() throws Exception {
        var player = identities.recordSeen(UUID.randomUUID(), "SkillRace");
        onboarding.ensureStarted(player.playerId());

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<FirstSkillCurrencyResult> mining = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return firstSkillCurrency.recordLevelUp(
                        player.playerId(),
                        "auraskills/mining",
                        2,
                        UUID.randomUUID()
                );
            });
            Future<FirstSkillCurrencyResult> farming = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return firstSkillCurrency.recordLevelUp(
                        player.playerId(),
                        "auraskills/farming",
                        2,
                        UUID.randomUUID()
                );
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<FirstSkillCurrencyResult> results = List.of(
                    mining.get(10, TimeUnit.SECONDS),
                    farming.get(10, TimeUnit.SECONDS)
            );

            assertEquals(1L, results.stream().filter(FirstSkillCurrencyResult::firstSkillRecorded).count());
            assertEquals(1L, results.stream().filter(FirstSkillCurrencyResult::currencyGranted).count());
        }

        OnboardingProgress progress = onboarding.find(player.playerId()).orElseThrow();
        assertTrue(progress.firstSkillLeveled());
        assertTrue(progress.firstCurrencyEarned());
        assertTrue(Set.of("auraskills/mining", "auraskills/farming").contains(progress.firstSkillId()));
        assertEquals(2, progress.firstSkillLevel());
        assertEquals(FirstSkillCurrencyService.STARTER_REWARD, economy.balance(player.playerId(), "COINS"));
        assertEquals(1L, countRows("economy_transaction"));
        assertEquals(1L, countRows("audit_event"));
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
