package dev.thedusk.minecraftserver.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thedusk.minecraftserver.audit.AuditLog;
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
class EncounterRunRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_encounter_test")
            .withUsername("minecraft_test")
            .withPassword("minecraft_test_password");

    private static final EncounterDefinition DEFINITION = new EncounterDefinition(
            "framework_probe",
            1,
            1,
            4,
            300
    );

    private static DatabaseManager database;
    private static PlayerIdentityRepository identities;
    private static EncounterRunRepository encounters;

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
                    "TRUNCATE TABLE encounter_participant, encounter_run, audit_event, player_identity CASCADE"
            );
        }
    }

    @Test
    void startPersistsDefinitionParticipantsAndExactReplay() throws Exception {
        PlayerIdentity one = player("EncounterOne");
        PlayerIdentity two = player("EncounterTwo");
        UUID correlationId = UUID.randomUUID();

        EncounterRunResult first = encounters.start(
                DEFINITION,
                List.of(two.playerId(), one.playerId()),
                correlationId
        );
        EncounterRunResult replay = encounters.start(
                DEFINITION,
                List.of(one.playerId(), two.playerId()),
                correlationId
        );

        assertFalse(first.idempotentReplay());
        assertTrue(replay.idempotentReplay());
        assertEquals(first.run().runId(), replay.run().runId());
        assertEquals(EncounterRunStatus.ACTIVE, first.run().status());
        assertEquals(DEFINITION.definitionSha256(), first.run().definitionSha256());
        assertEquals(
                List.of(one.playerId(), two.playerId()).stream().sorted().toList(),
                first.run().participantPlayerIds()
        );
        assertEquals(1L, countRows("encounter_run"));
        assertEquals(2L, countRows("encounter_participant"));
        assertEquals(1L, countAudit("encounter.run_started"));
    }

    @Test
    void reusedStartCorrelationWithDifferentSemanticsFailsClosed() throws Exception {
        PlayerIdentity one = player("ConflictOne");
        PlayerIdentity two = player("ConflictTwo");
        UUID correlationId = UUID.randomUUID();
        encounters.start(DEFINITION, List.of(one.playerId()), correlationId);

        EncounterException exception = assertThrows(
                EncounterException.class,
                () -> encounters.start(
                        DEFINITION,
                        List.of(one.playerId(), two.playerId()),
                        correlationId
                )
        );

        assertEquals(EncounterException.Code.IDEMPOTENCY_CONFLICT, exception.code());
        assertEquals(1L, countRows("encounter_run"));
        assertEquals(1L, countRows("encounter_participant"));
        assertEquals(1L, countAudit("encounter.run_started"));
    }

    @Test
    void terminalReplayIsIdempotentAndConflictingOutcomeFails() throws Exception {
        PlayerIdentity player = player("TerminalPlayer");
        EncounterRun started = encounters.start(
                DEFINITION,
                List.of(player.playerId()),
                UUID.randomUUID()
        ).run();

        EncounterRunResult completed = encounters.complete(started.runId(), UUID.randomUUID());
        EncounterRunResult replay = encounters.complete(started.runId(), UUID.randomUUID());

        assertFalse(completed.idempotentReplay());
        assertTrue(replay.idempotentReplay());
        assertEquals(EncounterRunStatus.COMPLETED, completed.run().status());
        assertNotNull(completed.run().endedAt());
        assertNotNull(completed.run().terminalCorrelationId());
        assertNull(completed.run().terminalReason());
        assertEquals(1L, countAudit("encounter.run_completed"));

        EncounterException conflict = assertThrows(
                EncounterException.class,
                () -> encounters.fail(started.runId(), UUID.randomUUID(), "LATE_FAILURE")
        );
        assertEquals(EncounterException.Code.INVALID_STATE, conflict.code());
        assertEquals(
                EncounterRunStatus.COMPLETED,
                encounters.find(started.runId()).orElseThrow().status()
        );
    }

    @Test
    void restartRecoveryAbandonsActiveRunsExactlyOnce() throws Exception {
        PlayerIdentity player = player("RestartPlayer");
        EncounterRun active = encounters.start(
                DEFINITION,
                List.of(player.playerId()),
                UUID.randomUUID()
        ).run();

        database.close();
        openServices();

        UUID recoveryCorrelation = UUID.randomUUID();
        assertEquals(1, encounters.recoverInterruptedRuns(recoveryCorrelation));
        assertEquals(0, encounters.recoverInterruptedRuns(UUID.randomUUID()));

        EncounterRun recovered = encounters.find(active.runId()).orElseThrow();
        assertEquals(EncounterRunStatus.ABANDONED, recovered.status());
        assertEquals("SERVER_RESTART", recovered.terminalReason());
        assertEquals(recoveryCorrelation, recovered.terminalCorrelationId());
        assertNotNull(recovered.endedAt());
        assertEquals(1L, countAudit("encounter.run_abandoned"));
    }

    @Test
    void concurrentConflictingTerminalTransitionsHaveOneWinner() throws Exception {
        PlayerIdentity player = player("ConcurrentPlayer");
        EncounterRun active = encounters.start(
                DEFINITION,
                List.of(player.playerId()),
                UUID.randomUUID()
        ).run();

        List<Attempt> attempts = terminateConcurrently(active.runId());

        assertEquals(1L, attempts.stream().filter(Attempt::success).count());
        assertEquals(1L, attempts.stream().filter(attempt -> !attempt.success()).count());
        Attempt failed = attempts.stream().filter(attempt -> !attempt.success()).findFirst().orElseThrow();
        assertTrue(failed.failure() instanceof EncounterException);
        assertEquals(
                EncounterException.Code.INVALID_STATE,
                ((EncounterException) failed.failure()).code()
        );
        EncounterRun durable = encounters.find(active.runId()).orElseThrow();
        assertTrue(
                durable.status() == EncounterRunStatus.COMPLETED
                        || durable.status() == EncounterRunStatus.FAILED
        );
        assertEquals(1L, countTerminalAudits());
    }

    @Test
    void startAuditConflictRollsBackRunAndParticipants() throws Exception {
        PlayerIdentity player = player("StartRollback");
        UUID correlationId = UUID.randomUUID();
        reserveAuditKey("encounter:start:" + correlationId);

        EncounterException exception = assertThrows(
                EncounterException.class,
                () -> encounters.start(DEFINITION, List.of(player.playerId()), correlationId)
        );

        assertEquals(EncounterException.Code.IDEMPOTENCY_CONFLICT, exception.code());
        assertEquals(0L, countRows("encounter_run"));
        assertEquals(0L, countRows("encounter_participant"));
    }

    @Test
    void terminalAuditConflictRollsBackTerminalState() throws Exception {
        PlayerIdentity player = player("TerminalRollback");
        EncounterRun active = encounters.start(
                DEFINITION,
                List.of(player.playerId()),
                UUID.randomUUID()
        ).run();
        reserveAuditKey("encounter:run:" + active.runId() + ":terminal");

        EncounterException exception = assertThrows(
                EncounterException.class,
                () -> encounters.complete(active.runId(), UUID.randomUUID())
        );

        assertEquals(EncounterException.Code.IDEMPOTENCY_CONFLICT, exception.code());
        EncounterRun durable = encounters.find(active.runId()).orElseThrow();
        assertEquals(EncounterRunStatus.ACTIVE, durable.status());
        assertNull(durable.endedAt());
        assertNull(durable.terminalCorrelationId());
        assertEquals(0L, countAudit("encounter.run_completed"));
    }

    private static List<Attempt> terminateConcurrently(UUID runId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> terminalAttempt(
                    ready,
                    start,
                    () -> encounters.complete(runId, UUID.randomUUID())
            )));
            futures.add(executor.submit(() -> terminalAttempt(
                    ready,
                    start,
                    () -> encounters.fail(runId, UUID.randomUUID(), "SYNTHETIC_FAILURE")
            )));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(10, TimeUnit.SECONDS));
            }
            return attempts;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static Attempt terminalAttempt(
            CountDownLatch ready,
            CountDownLatch start,
            Runnable operation
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for encounter terminal barrier");
        }
        try {
            operation.run();
            return new Attempt(true, null);
        } catch (RuntimeException exception) {
            return new Attempt(false, exception);
        }
    }

    private static void openServices() {
        database = DatabaseManager.open(new DatabaseSettings(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                6
        ));
        identities = new PlayerIdentityRepository(database);
        encounters = new EncounterRunRepository(database, new AuditLog());
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

    private static long countTerminalAudits() throws Exception {
        try (var connection = database.connection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM audit_event WHERE event_type IN (?, ?)"
             )) {
            statement.setString(1, "encounter.run_completed");
            statement.setString(2, "encounter.run_failed");
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

    private record Attempt(boolean success, RuntimeException failure) {
    }
}
