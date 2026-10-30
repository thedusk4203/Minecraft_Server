package dev.thedusk.minecraftserver.reward;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
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
class StoneWardenItemRewardRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_item_reward_test")
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
    private static StoneWardenRewardService currencyRewards;
    private static StoneWardenItemRewardRepository itemRewards;

    @BeforeAll
    static void openDatabase() {
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
        currencyRewards = new StoneWardenRewardService(
                database,
                auditLog,
                new EconomyLedger(database, auditLog)
        );
        itemRewards = new StoneWardenItemRewardRepository(database, auditLog);
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
                    TRUNCATE TABLE encounter_reward_item, encounter_reward,
                                   economy_transaction, economy_account,
                                   encounter_participant, encounter_run,
                                   audit_event, player_identity
                    CASCADE
                    """);
        }
    }

    @Test
    void completedButCurrencyPendingRewardCannotCreateItemIntent() throws Exception {
        PlayerIdentity player = player("ItemPending");
        EncounterRun run = encounters.start(
                STONE_WARDEN,
                List.of(player.playerId()),
                UUID.randomUUID()
        ).run();
        assertEquals(1, currencyRewards.prepare(run, UUID.randomUUID()));
        encounters.complete(run.runId(), UUID.randomUUID());

        assertEquals(0, itemRewards.prepareEligibleIntents(UUID.randomUUID()));
        assertEquals(0L, countRows("encounter_reward_item"));
    }

    @Test
    void grantedRewardCreatesOneStableItemIntent() throws Exception {
        Scenario scenario = completedGrantedReward("ItemStable");

        assertEquals(1, itemRewards.prepareEligibleIntents(UUID.randomUUID()));
        assertEquals(0, itemRewards.prepareEligibleIntents(UUID.randomUUID()));

        List<StoneWardenItemReward> rows = itemRewards.findByPlayer(scenario.player().playerId());
        assertEquals(1, rows.size());
        StoneWardenItemReward row = rows.getFirst();
        assertEquals(scenario.rewardId(), row.rewardId());
        assertEquals(scenario.rewardId(), row.deliveryToken());
        assertEquals(StoneWardenItemRewardDefinition.ITEM_KEY, row.itemKey());
        assertEquals(StoneWardenItemRewardDefinition.ITEM_VERSION, row.itemVersion());
        assertEquals(StoneWardenItemRewardStatus.PREPARED, row.status());
        assertEquals(1L, countAudit("encounter.reward_item_prepared"));
    }

    @Test
    void itemIntentAuditConflictRollsBackIntentInsert() throws Exception {
        Scenario scenario = completedGrantedReward("ItemIntentRollback");
        reserveAuditKey("encounter:reward:" + scenario.rewardId() + ":item-prepared");

        EncounterRewardException exception = assertThrows(
                EncounterRewardException.class,
                () -> itemRewards.prepareEligibleIntents(UUID.randomUUID())
        );

        assertEquals(EncounterRewardException.Code.SEMANTIC_CONFLICT, exception.code());
        assertEquals(0L, countRows("encounter_reward_item"));
    }

    @Test
    void snapshotAuditConflictRollsBackSnapshotTransition() throws Exception {
        Scenario scenario = completedGrantedReward("ItemSnapshotRollback");
        itemRewards.prepareEligibleIntents(UUID.randomUUID());
        byte[] payload = new byte[]{1, 2, 3, 4};
        reserveAuditKey("encounter:reward:" + scenario.rewardId() + ":item-snapshot");

        EncounterRewardException exception = assertThrows(
                EncounterRewardException.class,
                () -> itemRewards.prepareSnapshot(
                        scenario.rewardId(),
                        payload,
                        sha256(payload),
                        UUID.randomUUID()
                )
        );

        assertEquals(EncounterRewardException.Code.SEMANTIC_CONFLICT, exception.code());
        StoneWardenItemReward row = onlyItem(scenario.player().playerId());
        assertEquals(StoneWardenItemRewardStatus.PREPARED, row.status());
        assertEquals(null, row.itemPayload());
        assertEquals(null, row.itemSha256());
    }

    @Test
    void deliveryAuditConflictRollsBackTerminalAcknowledgement() throws Exception {
        Scenario scenario = completedGrantedReward("ItemDeliveryRollback");
        itemRewards.prepareEligibleIntents(UUID.randomUUID());
        byte[] payload = new byte[]{9, 8, 7, 6};
        itemRewards.prepareSnapshot(
                scenario.rewardId(),
                payload,
                sha256(payload),
                UUID.randomUUID()
        );
        reserveAuditKey("encounter:reward:" + scenario.rewardId() + ":item-delivered");

        EncounterRewardException exception = assertThrows(
                EncounterRewardException.class,
                () -> itemRewards.markDelivered(scenario.rewardId(), UUID.randomUUID())
        );

        assertEquals(EncounterRewardException.Code.SEMANTIC_CONFLICT, exception.code());
        StoneWardenItemReward row = onlyItem(scenario.player().playerId());
        assertEquals(StoneWardenItemRewardStatus.DELIVERY_PENDING, row.status());
        assertArrayEquals(payload, row.itemPayload());
    }

    @Test
    void concurrentDeliveryAcknowledgementConvergesOnOneTerminalAudit() throws Exception {
        Scenario scenario = completedGrantedReward("ItemDeliveryRace");
        itemRewards.prepareEligibleIntents(UUID.randomUUID());
        byte[] payload = new byte[]{5, 4, 3, 2, 1};
        itemRewards.prepareSnapshot(
                scenario.rewardId(),
                payload,
                sha256(payload),
                UUID.randomUUID()
        );

        List<StoneWardenItemReward> results = deliverConcurrently(scenario.rewardId());

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(row -> row.status() == StoneWardenItemRewardStatus.DELIVERED));
        assertEquals(1L, countAudit("encounter.reward_item_delivered"));
        assertEquals(StoneWardenItemRewardStatus.DELIVERED,
                onlyItem(scenario.player().playerId()).status());
    }

    @Test
    void persistedSemanticDriftFailsClosed() throws Exception {
        Scenario scenario = completedGrantedReward("ItemSemanticDrift");
        itemRewards.prepareEligibleIntents(UUID.randomUUID());
        try (var connection = database.connection();
             var statement = connection.prepareStatement(
                     "UPDATE encounter_reward_item SET item_key = 'different_reward' WHERE reward_id = ?"
             )) {
            statement.setObject(1, scenario.rewardId());
            assertEquals(1, statement.executeUpdate());
        }

        EncounterRewardException exception = assertThrows(
                EncounterRewardException.class,
                () -> itemRewards.findByPlayer(scenario.player().playerId())
        );
        assertEquals(EncounterRewardException.Code.SEMANTIC_CONFLICT, exception.code());
    }

    private static Scenario completedGrantedReward(String name) throws Exception {
        PlayerIdentity player = player(name);
        EncounterRun run = encounters.start(
                STONE_WARDEN,
                List.of(player.playerId()),
                UUID.randomUUID()
        ).run();
        encounters.complete(run.runId(), UUID.randomUUID());
        currencyRewards.grantCompleted(run.runId(), UUID.randomUUID());
        return new Scenario(player, run, onlyRewardId(run.runId()));
    }

    private static List<StoneWardenItemReward> deliverConcurrently(UUID rewardId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<StoneWardenItemReward>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for item delivery race barrier");
                    }
                    return itemRewards.markDelivered(rewardId, UUID.randomUUID());
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<StoneWardenItemReward> results = new ArrayList<>();
            for (Future<StoneWardenItemReward> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static StoneWardenItemReward onlyItem(UUID playerId) {
        List<StoneWardenItemReward> rows = itemRewards.findByPlayer(playerId);
        assertEquals(1, rows.size());
        return rows.getFirst();
    }

    private static PlayerIdentity player(String name) {
        return identities.recordSeen(UUID.randomUUID(), name);
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

    private static long countRows(String table) throws Exception {
        try (var connection = database.connection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
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

    private static String sha256(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }

    private record Scenario(PlayerIdentity player, EncounterRun run, UUID rewardId) {
    }
}
