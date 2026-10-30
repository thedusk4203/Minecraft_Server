package dev.thedusk.minecraftserver.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.encounter.EncounterDefinition;
import dev.thedusk.minecraftserver.encounter.EncounterRun;
import dev.thedusk.minecraftserver.encounter.EncounterRunRepository;
import dev.thedusk.minecraftserver.identity.PlayerIdentity;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.DatabaseSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
class StoneWardenRewardServiceIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_reward_test")
            .withUsername("minecraft_test")
            .withPassword("minecraft_test_password");

    private static final EncounterDefinition STONE_WARDEN = new EncounterDefinition(
            StoneWardenRewardService.ENCOUNTER_ID,
            1,
            1,
            4,
            300
    );

    private static DatabaseManager database;
    private static PlayerIdentityRepository identities;
    private static EncounterRunRepository encounters;
    private static EconomyLedger economy;
    private static StoneWardenRewardService rewards;

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
            statement.execute("""
                    TRUNCATE TABLE encounter_reward, economy_transaction, economy_account,
                                   encounter_participant, encounter_run, audit_event, player_identity
                    CASCADE
                    """);
        }
    }

    @Test
    void activeRunCannotGrantCurrency() {
        PlayerIdentity player = player("RewardActive");
        EncounterRun run = encounters.start(
                STONE_WARDEN,
                List.of(player.playerId()),
                UUID.randomUUID()
        ).run();

        EncounterRewardException exception = assertThrows(
                EncounterRewardException.class,
                () -> rewards.grantCompleted(run.runId(), UUID.randomUUID())
        );

        assertEquals(EncounterRewardException.Code.INVALID_STATE, exception.code());
        assertEquals(0L, economy.balance(player.playerId(), StoneWardenRewardService.CURRENCY_CODE));
    }

    @Test
    void completedRunCreatesMissingIntentsAndGrantsExactlyOnce() throws Exception {
        PlayerIdentity one = player("RewardOne");
        PlayerIdentity two = player("RewardTwo");
        EncounterRun run = encounters.start(
                STONE_WARDEN,
                List.of(two.playerId(), one.playerId()),
                UUID.randomUUID()
        ).run();
        encounters.complete(run.runId(), UUID.randomUUID());

        RewardGrantSummary first = rewards.grantCompleted(run.runId(), UUID.randomUUID());
        RewardGrantSummary replay = rewards.grantCompleted(run.runId(), UUID.randomUUID());

        assertEquals(2, first.participantCount());
        assertEquals(2, first.newlyGrantedCount());
        assertEquals(0, first.replayedCount());
        assertEquals(0, replay.newlyGrantedCount());
        assertEquals(2, replay.replayedCount());
        assertEquals(StoneWardenRewardService.CURRENCY_AMOUNT,
                economy.balance(one.playerId(), StoneWardenRewardService.CURRENCY_CODE));
        assertEquals(StoneWardenRewardService.CURRENCY_AMOUNT,
                economy.balance(two.playerId(), StoneWardenRewardService.CURRENCY_CODE));
        assertEquals(2L, countRows("encounter_reward"));
        assertEquals(2L, countRewardStatus("CURRENCY_GRANTED"));
        assertEquals(2L, countEconomyReason("STONE_WARDEN_REWARD"));
        assertEquals(2L, countAudit("encounter.reward_prepared"));
        assertEquals(2L, countAudit("encounter.reward_currency_granted"));
    }

    @Test
    void reconcileFindsCompletedRunWithoutExistingRewardRows() {
        PlayerIdentity player = player("RewardRecovery");
        EncounterRun run = encounters.start(
                STONE_WARDEN,
                List.of(player.playerId()),
                UUID.randomUUID()
        ).run();
        encounters.complete(run.runId(), UUID.randomUUID());

        assertEquals(1, rewards.reconcileCompletedPending());
        assertEquals(0, rewards.reconcileCompletedPending());
        assertEquals(StoneWardenRewardService.CURRENCY_AMOUNT,
                economy.balance(player.playerId(), StoneWardenRewardService.CURRENCY_CODE));
    }

    @Test
    void concurrentGrantCallsStillCreditEachParticipantOnce() throws Exception {
        PlayerIdentity one = player("RewardRaceOne");
        PlayerIdentity two = player("RewardRaceTwo");
        EncounterRun run = encounters.start(
                STONE_WARDEN,
                List.of(one.playerId(), two.playerId()),
                UUID.randomUUID()
        ).run();
        encounters.complete(run.runId(), UUID.randomUUID());

        List<RewardGrantSummary> results = grantConcurrently(run.runId());

        assertEquals(2, results.size());
        assertEquals(2, results.stream().mapToInt(RewardGrantSummary::newlyGrantedCount).sum());
        assertEquals(2, results.stream().mapToInt(RewardGrantSummary::replayedCount).sum());
        assertEquals(StoneWardenRewardService.CURRENCY_AMOUNT,
                economy.balance(one.playerId(), StoneWardenRewardService.CURRENCY_CODE));
        assertEquals(StoneWardenRewardService.CURRENCY_AMOUNT,
                economy.balance(two.playerId(), StoneWardenRewardService.CURRENCY_CODE));
        assertEquals(2L, countEconomyReason("STONE_WARDEN_REWARD"));
    }

    @Test
    void grantAuditConflictRollsBackLedgerAndRewardState() throws Exception {
        PlayerIdentity player = player("RewardRollback");
        EncounterRun run = encounters.start(
                STONE_WARDEN,
                List.of(player.playerId()),
                UUID.randomUUID()
        ).run();
        assertEquals(1, rewards.prepare(run, UUID.randomUUID()));
        UUID rewardId = onlyRewardId(run.runId());
        encounters.complete(run.runId(), UUID.randomUUID());
        reserveAuditKey("encounter:reward:" + rewardId + ":granted");

        EncounterRewardException exception = assertThrows(
                EncounterRewardException.class,
                () -> rewards.grantCompleted(run.runId(), UUID.randomUUID())
        );

        assertEquals(EncounterRewardException.Code.SEMANTIC_CONFLICT, exception.code());
        assertEquals(0L, economy.balance(player.playerId(), StoneWardenRewardService.CURRENCY_CODE));
        assertEquals(0L, countEconomyReason("STONE_WARDEN_REWARD"));
        assertEquals(1L, countRewardStatus("PENDING"));
        assertEquals(0L, countRewardStatus("CURRENCY_GRANTED"));
    }

    private static List<RewardGrantSummary> grantConcurrently(UUID runId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RewardGrantSummary>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for reward race barrier");
                    }
                    return rewards.grantCompleted(runId, UUID.randomUUID());
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<RewardGrantSummary> results = new ArrayList<>();
            for (Future<RewardGrantSummary> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static void openServices() {
        database = DatabaseManager.open(new DatabaseSettings(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                8
        ));
        AuditLog auditLog = new AuditLog();
        identities = new PlayerIdentityRepository(database);
        encounters = new EncounterRunRepository(database, auditLog);
        economy = new EconomyLedger(database, auditLog);
        rewards = new StoneWardenRewardService(database, auditLog, economy);
    }

    private static PlayerIdentity player(String name) {
        return identities.recordSeen(UUID.randomUUID(), name);
    }

    private static long countRows(String table) throws Exception {
        try (var connection = database.connection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long countRewardStatus(String status) throws Exception {
        try (var connection = database.connection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM encounter_reward WHERE status = ?"
             )) {
            statement.setString(1, status);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long countEconomyReason(String reason) throws Exception {
        try (var connection = database.connection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM economy_transaction WHERE reason = ?"
             )) {
            statement.setString(1, reason);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long countAudit(String eventType) throws Exception {
        try (var connection = database.connection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM audit_event WHERE event_type = ?"
             )) {
            statement.setString(1, eventType);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static UUID onlyRewardId(UUID runId) throws Exception {
        try (var connection = database.connection();
             var statement = connection.prepareStatement(
                     "SELECT reward_id FROM encounter_reward WHERE run_id = ?"
             )) {
            statement.setObject(1, runId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                UUID rewardId = result.getObject(1, UUID.class);
                assertTrue(!result.next());
                return rewardId;
            }
        }
    }

    private static void reserveAuditKey(String key) throws Exception {
        try (var connection = database.connection();
             var statement = connection.prepareStatement("""
                     INSERT INTO audit_event (
                         event_id, actor_player_id, event_type, correlation_id,
                         idempotency_key, payload, occurred_at
                     )
                     VALUES (?, NULL, 'test.reserved', ?, ?, '{}'::jsonb, NOW())
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, UUID.randomUUID());
            statement.setString(3, key);
            statement.executeUpdate();
        }
    }
}
