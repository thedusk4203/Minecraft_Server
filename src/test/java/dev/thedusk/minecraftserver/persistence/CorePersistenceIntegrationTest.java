package dev.thedusk.minecraftserver.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thedusk.minecraftserver.admin.AdminDiagnosticsService;
import dev.thedusk.minecraftserver.admin.AdminDiagnosticsSnapshot;
import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.economy.EconomyException;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.economy.EconomyMutation;
import dev.thedusk.minecraftserver.economy.EconomyResult;
import dev.thedusk.minecraftserver.identity.PlayerIdentity;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import java.sql.SQLException;
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
class CorePersistenceIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_test")
            .withUsername("minecraft_test")
            .withPassword("minecraft_test_password");

    private static DatabaseManager database;
    private static PlayerIdentityRepository identities;
    private static EconomyLedger economy;
    private static AdminDiagnosticsService diagnostics;

    @BeforeAll
    static void openDatabase() {
        database = DatabaseManager.open(new DatabaseSettings(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                4
        ));
        identities = new PlayerIdentityRepository(database);
        AuditLog auditLog = new AuditLog();
        economy = new EconomyLedger(database, auditLog);
        diagnostics = new AdminDiagnosticsService(database, auditLog);
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void clearDurableState() throws SQLException {
        try (var connection = database.connection();
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE economy_transaction, economy_account, audit_event, player_identity CASCADE");
        }
    }

    @Test
    void identityUpsertKeepsStableInternalPlayerId() {
        UUID minecraftUuid = UUID.randomUUID();

        PlayerIdentity first = identities.recordSeen(minecraftUuid, "Alice");
        PlayerIdentity second = identities.recordSeen(minecraftUuid, "Alice2");

        assertEquals(first.playerId(), second.playerId());
        assertEquals(minecraftUuid, second.minecraftUuid());
        assertEquals("Alice2", second.lastKnownName());
    }

    @Test
    void idempotentRetryDoesNotDoubleCredit() throws SQLException {
        PlayerIdentity player = identities.recordSeen(UUID.randomUUID(), "LedgerUser");
        UUID correlationId = UUID.randomUUID();
        EconomyMutation mutation = new EconomyMutation(
                player.playerId(),
                "COINS",
                100L,
                "test.credit",
                "test-credit-once",
                correlationId,
                "{\"source\":\"integration-test\"}"
        );

        EconomyResult first = economy.apply(mutation);
        EconomyResult replay = economy.apply(mutation);

        assertFalse(first.idempotentReplay());
        assertTrue(replay.idempotentReplay());
        assertEquals(first.transactionId(), replay.transactionId());
        assertEquals(100L, replay.balanceAfter());
        assertEquals(100L, economy.balance(player.playerId(), "coins"));
        assertEquals(1L, countRows("economy_transaction"));
        assertEquals(1L, countRows("audit_event"));
    }

    @Test
    void concurrentDuplicateCreditIsAppliedExactlyOnce() throws Exception {
        PlayerIdentity player = identities.recordSeen(UUID.randomUUID(), "RaceReplay");
        EconomyMutation mutation = new EconomyMutation(
                player.playerId(),
                "COINS",
                100L,
                "test.concurrent-credit",
                "test-concurrent-credit-once",
                UUID.randomUUID(),
                "{}"
        );

        List<EconomyResult> results = applyConcurrently(mutation, mutation);

        assertEquals(1L, results.stream().filter(EconomyResult::idempotentReplay).count());
        assertEquals(results.get(0).transactionId(), results.get(1).transactionId());
        assertEquals(100L, economy.balance(player.playerId(), "COINS"));
        assertEquals(1L, countRows("economy_transaction"));
        assertEquals(1L, countRows("audit_event"));
    }

    @Test
    void concurrentDistinctCreditsSerializeWithoutLostUpdates() throws Exception {
        PlayerIdentity player = identities.recordSeen(UUID.randomUUID(), "RaceBalance");
        EconomyMutation first = new EconomyMutation(
                player.playerId(),
                "COINS",
                100L,
                "test.concurrent-credit",
                "test-concurrent-credit-a",
                UUID.randomUUID(),
                "{}"
        );
        EconomyMutation second = new EconomyMutation(
                player.playerId(),
                "COINS",
                150L,
                "test.concurrent-credit",
                "test-concurrent-credit-b",
                UUID.randomUUID(),
                "{}"
        );

        List<EconomyResult> results = applyConcurrently(first, second);

        assertEquals(2, results.size());
        assertTrue(results.stream().noneMatch(EconomyResult::idempotentReplay));
        assertEquals(250L, economy.balance(player.playerId(), "COINS"));
        assertEquals(2L, countRows("economy_transaction"));
        assertEquals(2L, countRows("audit_event"));
    }

    @Test
    void debitCannotMakeBalanceNegative() throws SQLException {
        PlayerIdentity player = identities.recordSeen(UUID.randomUUID(), "NoDebtUser");
        EconomyMutation mutation = new EconomyMutation(
                player.playerId(),
                "COINS",
                -1L,
                "test.debit",
                "test-negative-balance",
                UUID.randomUUID(),
                "{}"
        );

        EconomyException exception = assertThrows(
                EconomyException.class,
                () -> economy.apply(mutation)
        );

        assertEquals(EconomyException.Code.INSUFFICIENT_FUNDS, exception.code());
        assertEquals(0L, economy.balance(player.playerId(), "COINS"));
        assertEquals(0L, countRows("economy_transaction"));
        assertEquals(0L, countRows("audit_event"));
    }

    @Test
    void reusingIdempotencyKeyForDifferentMutationFailsClosed() {
        PlayerIdentity player = identities.recordSeen(UUID.randomUUID(), "ConflictUser");
        String key = "test-idempotency-conflict";

        economy.apply(new EconomyMutation(
                player.playerId(),
                "COINS",
                100L,
                "test.credit",
                key,
                UUID.randomUUID(),
                "{}"
        ));

        EconomyException exception = assertThrows(
                EconomyException.class,
                () -> economy.apply(new EconomyMutation(
                        player.playerId(),
                        "COINS",
                        200L,
                        "test.credit",
                        key,
                        UUID.randomUUID(),
                        "{}"
                ))
        );

        assertEquals(EconomyException.Code.IDEMPOTENCY_CONFLICT, exception.code());
        assertEquals(100L, economy.balance(player.playerId(), "COINS"));
    }

    @Test
    void adminDiagnosticsReportsDurableStateAndAuditsActor() throws SQLException {
        UUID minecraftUuid = UUID.randomUUID();
        PlayerIdentity player = identities.recordSeen(minecraftUuid, "AdminUser");
        economy.apply(new EconomyMutation(
                player.playerId(),
                "COINS",
                250L,
                "test.admin-seed",
                "test-admin-seed-once",
                UUID.randomUUID(),
                "{}"
        ));
        UUID correlationId = UUID.randomUUID();

        AdminDiagnosticsSnapshot snapshot = diagnostics.collect(minecraftUuid, correlationId);

        assertEquals(1L, snapshot.knownPlayers());
        assertEquals(1L, snapshot.economyAccounts());
        assertEquals(1L, snapshot.economyTransactions());
        assertEquals(2L, snapshot.auditEvents());
        assertAdminAudit(player.playerId(), correlationId, "diagnostics");
    }

    private static List<EconomyResult> applyConcurrently(EconomyMutation... mutations) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(mutations.length);
        CountDownLatch ready = new CountDownLatch(mutations.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<EconomyResult>> futures = new ArrayList<>();

        try {
            for (EconomyMutation mutation : mutations) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent economy start gate");
                    }
                    return economy.apply(mutation);
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "Workers did not reach concurrency barrier");
            start.countDown();

            List<EconomyResult> results = new ArrayList<>();
            for (Future<EconomyResult> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertAdminAudit(
            UUID expectedActorPlayerId,
            UUID expectedCorrelationId,
            String expectedSubcommand
    ) throws SQLException {
        try (var connection = database.connection();
             var statement = connection.prepareStatement("""
                     SELECT actor_player_id, correlation_id, payload ->> 'subcommand' AS subcommand
                     FROM audit_event
                     WHERE event_type = 'admin.command'
                     ORDER BY occurred_at DESC
                     LIMIT 1
                     """)) {
            try (var result = statement.executeQuery()) {
                assertTrue(result.next(), "Expected an admin.command audit event");
                assertEquals(expectedActorPlayerId, result.getObject("actor_player_id", UUID.class));
                assertEquals(expectedCorrelationId, result.getObject("correlation_id", UUID.class));
                assertEquals(expectedSubcommand, result.getString("subcommand"));
            }
        }
    }

    private static long countRows(String table) throws SQLException {
        try (var connection = database.connection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }
}
