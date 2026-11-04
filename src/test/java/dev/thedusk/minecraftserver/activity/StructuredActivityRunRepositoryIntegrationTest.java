package dev.thedusk.minecraftserver.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.encounter.EncounterDefinition;
import dev.thedusk.minecraftserver.encounter.EncounterRun;
import dev.thedusk.minecraftserver.encounter.EncounterRunRepository;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.reward.StoneWardenItemRewardRepository;
import dev.thedusk.minecraftserver.reward.StoneWardenItemRewardStatus;
import dev.thedusk.minecraftserver.reward.StoneWardenRewardService;
import dev.thedusk.minecraftserver.identity.PlayerIdentity;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.DatabaseSettings;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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
class StructuredActivityRunRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_activity_test")
            .withUsername("minecraft_test")
            .withPassword("minecraft_test_password");

    private static final EncounterDefinition ENCOUNTER = new EncounterDefinition(
            "stone_warden",
            1,
            1,
            4,
            300
    );

    private static final StructuredActivityDefinition ACTIVITY =
            new StructuredActivityDefinition(
                    "stone_vault",
                    1,
                    "stone_warden",
                    1,
                    1,
                    4,
                    4,
                    2,
                    2,
                    18
            );

    private static DatabaseManager database;
    private static PlayerIdentityRepository identities;
    private static EncounterRunRepository encounters;
    private static StructuredActivityRunRepository activities;
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
                6
        ));
        AuditLog auditLog = new AuditLog();
        identities = new PlayerIdentityRepository(database);
        encounters = new EncounterRunRepository(database, auditLog);
        activities = new StructuredActivityRunRepository(database, auditLog);
        currencyRewards = new StoneWardenRewardService(
                database, auditLog, new EconomyLedger(database, auditLog)
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
            statement.execute(
                    "TRUNCATE TABLE structured_activity_run, encounter_participant, "
                            + "encounter_run, audit_event, player_identity CASCADE"
            );
        }
    }

    @Test
    void attachPersistsDefinitionAndExactReplay() throws Exception {
        EncounterRun encounter = startEncounter(2);
        UUID correlationId = UUID.randomUUID();

        StructuredActivityRunResult first = activities.attach(
                ACTIVITY,
                ENCOUNTER,
                encounter.runId(),
                correlationId
        );
        StructuredActivityRunResult replay = activities.attach(
                ACTIVITY,
                ENCOUNTER,
                encounter.runId(),
                UUID.randomUUID()
        );

        assertFalse(first.idempotentReplay());
        assertTrue(replay.idempotentReplay());
        assertEquals(encounter.runId(), first.run().runId());
        assertEquals("stone_vault", first.run().activityId());
        assertEquals(1, first.run().activityVersion());
        assertEquals(ACTIVITY.definitionSha256(), first.run().definitionSha256());
        assertEquals(StructuredActivityBeat.OUTER_SEAL, first.run().currentBeat());
        assertEquals(first.run(), replay.run());
        assertEquals(1L, countRows("structured_activity_run"));
        assertEquals(1L, countAudit("activity.run_attached"));
    }

    @Test
    void conflictingActivityDefinitionOnSameRunFailsClosed() throws Exception {
        EncounterRun encounter = startEncounter(1);
        activities.attach(ACTIVITY, ENCOUNTER, encounter.runId(), UUID.randomUUID());
        StructuredActivityDefinition drifted = new StructuredActivityDefinition(
                "stone_vault",
                1,
                "stone_warden",
                1,
                1,
                4,
                5,
                2,
                2,
                18
        );

        StructuredActivityException exception = assertThrows(
                StructuredActivityException.class,
                () -> activities.attach(
                        drifted,
                        ENCOUNTER,
                        encounter.runId(),
                        UUID.randomUUID()
                )
        );

        assertEquals(
                StructuredActivityException.Code.IDEMPOTENCY_CONFLICT,
                exception.code()
        );
        assertEquals(ACTIVITY.definitionSha256(),
                activities.find(encounter.runId()).orElseThrow().definitionSha256());
        assertEquals(1L, countAudit("activity.run_attached"));
    }

    @Test
    void wrongEncounterAuthorityFailsClosed() throws Exception {
        EncounterDefinition other = new EncounterDefinition(
                "framework_probe",
                1,
                1,
                4,
                300
        );
        PlayerIdentity player = player("WrongEncounter");
        EncounterRun encounter = encounters.start(
                other,
                List.of(player.playerId()),
                UUID.randomUUID()
        ).run();

        StructuredActivityException exception = assertThrows(
                StructuredActivityException.class,
                () -> activities.attach(
                        ACTIVITY,
                        ENCOUNTER,
                        encounter.runId(),
                        UUID.randomUUID()
                )
        );

        assertEquals(
                StructuredActivityException.Code.IDEMPOTENCY_CONFLICT,
                exception.code()
        );
        assertTrue(activities.find(encounter.runId()).isEmpty());
    }

    @Test
    void beatTransitionsAreMonotonicAndReplaySafe() throws Exception {
        EncounterRun encounter = startEncounter(2);
        activities.attach(ACTIVITY, ENCOUNTER, encounter.runId(), UUID.randomUUID());

        StructuredActivityRunResult beatTwo = activities.advance(
                encounter.runId(),
                StructuredActivityBeat.OUTER_SEAL,
                StructuredActivityBeat.INNER_GUARD,
                UUID.randomUUID()
        );
        StructuredActivityRunResult beatTwoReplay = activities.advance(
                encounter.runId(),
                StructuredActivityBeat.OUTER_SEAL,
                StructuredActivityBeat.INNER_GUARD,
                UUID.randomUUID()
        );
        StructuredActivityRunResult boss = activities.advance(
                encounter.runId(),
                StructuredActivityBeat.INNER_GUARD,
                StructuredActivityBeat.BOSS,
                UUID.randomUUID()
        );

        assertFalse(beatTwo.idempotentReplay());
        assertTrue(beatTwoReplay.idempotentReplay());
        assertEquals(StructuredActivityBeat.INNER_GUARD, beatTwo.run().currentBeat());
        assertEquals(StructuredActivityBeat.BOSS, boss.run().currentBeat());
        assertEquals(2L, countAudit("activity.beat_started"));
        assertEquals(2L, boss.run().version());
    }

    @Test
    void skippedOrOutOfOrderTransitionFailsClosed() throws Exception {
        EncounterRun encounter = startEncounter(1);
        activities.attach(ACTIVITY, ENCOUNTER, encounter.runId(), UUID.randomUUID());

        assertThrows(
                IllegalArgumentException.class,
                () -> activities.advance(
                        encounter.runId(),
                        StructuredActivityBeat.OUTER_SEAL,
                        StructuredActivityBeat.BOSS,
                        UUID.randomUUID()
                )
        );

        StructuredActivityException exception = assertThrows(
                StructuredActivityException.class,
                () -> activities.advance(
                        encounter.runId(),
                        StructuredActivityBeat.INNER_GUARD,
                        StructuredActivityBeat.BOSS,
                        UUID.randomUUID()
                )
        );
        assertEquals(StructuredActivityException.Code.INVALID_STATE, exception.code());
        assertEquals(
                StructuredActivityBeat.OUTER_SEAL,
                activities.find(encounter.runId()).orElseThrow().currentBeat()
        );
    }

    @Test
    void terminalEncounterCannotAdvanceActivity() throws Exception {
        EncounterRun encounter = startEncounter(1);
        activities.attach(ACTIVITY, ENCOUNTER, encounter.runId(), UUID.randomUUID());
        encounters.fail(encounter.runId(), UUID.randomUUID(), "TEST_FAILURE");

        StructuredActivityException exception = assertThrows(
                StructuredActivityException.class,
                () -> activities.advance(
                        encounter.runId(),
                        StructuredActivityBeat.OUTER_SEAL,
                        StructuredActivityBeat.INNER_GUARD,
                        UUID.randomUUID()
                )
        );

        assertEquals(StructuredActivityException.Code.INVALID_STATE, exception.code());
        assertEquals(
                StructuredActivityBeat.OUTER_SEAL,
                activities.find(encounter.runId()).orElseThrow().currentBeat()
        );
    }

    @Test
    void attachAuditConflictRollsBackActivityRow() throws Exception {
        EncounterRun encounter = startEncounter(1);
        reserveAuditKey("activity:run:" + encounter.runId() + ":attached");

        StructuredActivityException exception = assertThrows(
                StructuredActivityException.class,
                () -> activities.attach(
                        ACTIVITY,
                        ENCOUNTER,
                        encounter.runId(),
                        UUID.randomUUID()
                )
        );

        assertEquals(
                StructuredActivityException.Code.IDEMPOTENCY_CONFLICT,
                exception.code()
        );
        assertTrue(activities.find(encounter.runId()).isEmpty());
    }

    @Test
    void beatAuditConflictRollsBackTransition() throws Exception {
        EncounterRun encounter = startEncounter(1);
        activities.attach(ACTIVITY, ENCOUNTER, encounter.runId(), UUID.randomUUID());
        reserveAuditKey(
                "activity:run:" + encounter.runId() + ":beat:"
                        + StructuredActivityBeat.INNER_GUARD.ordinalValue()
        );

        StructuredActivityException exception = assertThrows(
                StructuredActivityException.class,
                () -> activities.advance(
                        encounter.runId(),
                        StructuredActivityBeat.OUTER_SEAL,
                        StructuredActivityBeat.INNER_GUARD,
                        UUID.randomUUID()
                )
        );

        assertEquals(
                StructuredActivityException.Code.IDEMPOTENCY_CONFLICT,
                exception.code()
        );
        StructuredActivityRun durable = activities.find(encounter.runId()).orElseThrow();
        assertEquals(StructuredActivityBeat.OUTER_SEAL, durable.currentBeat());
        assertEquals(0L, durable.version());
    }

    @Test
    void completedStoneVaultReconcilesAndReplaysFrozenRewardsWithoutDuplication() throws Exception {
        EncounterRun run = startEncounter(2);
        activities.attach(ACTIVITY, ENCOUNTER, run.runId(), UUID.randomUUID());
        activities.advance(run.runId(), StructuredActivityBeat.OUTER_SEAL,
                StructuredActivityBeat.INNER_GUARD, UUID.randomUUID());
        activities.advance(run.runId(), StructuredActivityBeat.INNER_GUARD,
                StructuredActivityBeat.BOSS, UUID.randomUUID());

        // The same V9-attached run is completed; it has no separate dungeon
        // economy identity or second boss encounter.
        encounters.complete(run.runId(), UUID.randomUUID());
        assertEquals(1, currencyRewards.reconcileCompletedPending());
        assertEquals(0, currencyRewards.reconcileCompletedPending());
        var replay = currencyRewards.grantCompleted(run.runId(), UUID.randomUUID());
        assertEquals(0, replay.newlyGrantedCount());
        assertEquals(2, replay.replayedCount());

        assertEquals(2, itemRewards.prepareEligibleIntents(UUID.randomUUID()));
        assertEquals(0, itemRewards.prepareEligibleIntents(UUID.randomUUID()));
        for (UUID participantId : run.participantPlayerIds()) {
            var records = itemRewards.findByPlayer(participantId);
            assertEquals(1, records.size());
            var record = records.getFirst();
            assertEquals(record.rewardId(), record.deliveryToken());
            assertEquals(StoneWardenItemRewardStatus.PREPARED, record.status());
            byte[] snapshot = new byte[]{1, 7, 2, 9};
            String sha256 = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(snapshot)
            );
            itemRewards.prepareSnapshot(record.rewardId(), snapshot, sha256, UUID.randomUUID());
            assertEquals(StoneWardenItemRewardStatus.DELIVERED,
                    itemRewards.markDelivered(record.rewardId(), UUID.randomUUID()).status());
            // Replaying acknowledgement must not create another terminal event.
            assertEquals(StoneWardenItemRewardStatus.DELIVERED,
                    itemRewards.markDelivered(record.rewardId(), UUID.randomUUID()).status());
        }
        assertEquals(0, itemRewards.prepareEligibleIntents(UUID.randomUUID()));
        assertEquals(0, currencyRewards.reconcileCompletedPending());

        assertEquals(1L, countRows("encounter_run"));
        assertEquals(1L, countRows("structured_activity_run"));
        assertEquals(2L, countRows("encounter_participant"));
        assertEquals(2L, countRows("encounter_reward"));
        assertEquals(2L, countRows("encounter_reward_item"));
        assertEquals(2L, countRows("economy_transaction"));
        assertEquals(1L, countAudit("encounter.run_completed"));
        assertEquals(2L, countAudit("encounter.reward_currency_granted"));
        assertEquals(2L, countAudit("encounter.reward_item_prepared"));
        assertEquals(2L, countAudit("encounter.reward_item_delivered"));
        assertEquals(StructuredActivityBeat.BOSS,
                activities.find(run.runId()).orElseThrow().currentBeat());
    }

    private static EncounterRun startEncounter(int participantCount) {
        List<UUID> participantIds = new java.util.ArrayList<>();
        for (int i = 0; i < participantCount; i++) {
            participantIds.add(player("ActivityPlayer" + i).playerId());
        }
        return encounters.start(
                ENCOUNTER,
                participantIds,
                UUID.randomUUID()
        ).run();
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
}
