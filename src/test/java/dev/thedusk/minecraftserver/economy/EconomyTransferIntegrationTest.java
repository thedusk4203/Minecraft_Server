package dev.thedusk.minecraftserver.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.identity.PlayerIdentity;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.DatabaseSettings;
import java.util.List;
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
class EconomyTransferIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_transfer_test")
            .withUsername("minecraft_test")
            .withPassword("minecraft_test_password");

    private static DatabaseManager database;
    private static PlayerIdentityRepository identities;
    private static EconomyLedger economy;

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
        economy = new EconomyLedger(database, new AuditLog());
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
                    "TRUNCATE TABLE economy_transaction, economy_account, audit_event, player_identity CASCADE"
            );
        }
    }

    @Test
    void transferDebitsAndCreditsAtomicallyAndAuditsSettlement() throws Exception {
        PlayerIdentity from = identities.recordSeen(UUID.randomUUID(), "TransferFrom");
        PlayerIdentity to = identities.recordSeen(UUID.randomUUID(), "TransferTo");
        seed(from, 100L, "seed-transfer-from");
        UUID correlationId = UUID.randomUUID();

        EconomyTransferResult result = economy.transfer(new EconomyTransfer(
                from.playerId(),
                to.playerId(),
                "coins",
                40L,
                "market.trade",
                "trade-atomic-1",
                correlationId,
                "{\"source\":\"integration-test\"}"
        ));

        assertFalse(result.idempotentReplay());
        assertEquals(60L, result.fromBalanceAfter());
        assertEquals(40L, result.toBalanceAfter());
        assertEquals(60L, economy.balance(from.playerId(), "COINS"));
        assertEquals(40L, economy.balance(to.playerId(), "COINS"));
        assertEquals(3L, countRows("economy_transaction"));
        assertEquals(4L, countRows("audit_event"));
        assertTransferAudit(from.playerId(), to.playerId(), correlationId, 40L);
    }

    @Test
    void retryReplaysSameTransferWithoutMovingMoneyTwice() throws Exception {
        PlayerIdentity from = identities.recordSeen(UUID.randomUUID(), "ReplayFrom");
        PlayerIdentity to = identities.recordSeen(UUID.randomUUID(), "ReplayTo");
        seed(from, 100L, "seed-transfer-replay");
        EconomyTransfer transfer = new EconomyTransfer(
                from.playerId(),
                to.playerId(),
                "COINS",
                25L,
                "market.trade",
                "trade-replay-1",
                UUID.randomUUID(),
                "{}"
        );

        EconomyTransferResult first = economy.transfer(transfer);
        EconomyTransferResult replay = economy.transfer(new EconomyTransfer(
                from.playerId(),
                to.playerId(),
                "COINS",
                25L,
                "market.trade",
                "trade-replay-1",
                UUID.randomUUID(),
                "{}"
        ));

        assertFalse(first.idempotentReplay());
        assertTrue(replay.idempotentReplay());
        assertEquals(first.debitTransactionId(), replay.debitTransactionId());
        assertEquals(first.creditTransactionId(), replay.creditTransactionId());
        assertEquals(first.correlationId(), replay.correlationId());
        assertEquals(75L, economy.balance(from.playerId(), "COINS"));
        assertEquals(25L, economy.balance(to.playerId(), "COINS"));
        assertEquals(3L, countRows("economy_transaction"));
        assertEquals(4L, countRows("audit_event"));
    }

    @Test
    void insufficientFundsRollBackBothSides() throws Exception {
        PlayerIdentity from = identities.recordSeen(UUID.randomUUID(), "PoorFrom");
        PlayerIdentity to = identities.recordSeen(UUID.randomUUID(), "PoorTo");
        seed(from, 10L, "seed-transfer-poor");

        EconomyException exception = assertThrows(
                EconomyException.class,
                () -> economy.transfer(new EconomyTransfer(
                        from.playerId(),
                        to.playerId(),
                        "COINS",
                        11L,
                        "market.trade",
                        "trade-insufficient-1",
                        UUID.randomUUID(),
                        "{}"
                ))
        );

        assertEquals(EconomyException.Code.INSUFFICIENT_FUNDS, exception.code());
        assertEquals(10L, economy.balance(from.playerId(), "COINS"));
        assertEquals(0L, economy.balance(to.playerId(), "COINS"));
        assertEquals(1L, countRows("economy_transaction"));
        assertEquals(1L, countRows("audit_event"));
    }

    @Test
    void reusingTransferKeyWithDifferentAmountFailsClosed() {
        PlayerIdentity from = identities.recordSeen(UUID.randomUUID(), "ConflictFrom");
        PlayerIdentity to = identities.recordSeen(UUID.randomUUID(), "ConflictTo");
        seed(from, 100L, "seed-transfer-conflict");
        String key = "trade-conflict-1";

        economy.transfer(new EconomyTransfer(
                from.playerId(),
                to.playerId(),
                "COINS",
                20L,
                "market.trade",
                key,
                UUID.randomUUID(),
                "{}"
        ));

        EconomyException exception = assertThrows(
                EconomyException.class,
                () -> economy.transfer(new EconomyTransfer(
                        from.playerId(),
                        to.playerId(),
                        "COINS",
                        21L,
                        "market.trade",
                        key,
                        UUID.randomUUID(),
                        "{}"
                ))
        );

        assertEquals(EconomyException.Code.IDEMPOTENCY_CONFLICT, exception.code());
        assertEquals(80L, economy.balance(from.playerId(), "COINS"));
        assertEquals(20L, economy.balance(to.playerId(), "COINS"));
    }

    @Test
    void oppositeDirectionTransfersUseStableLockOrder() throws Exception {
        PlayerIdentity alpha = identities.recordSeen(UUID.randomUUID(), "LockAlpha");
        PlayerIdentity beta = identities.recordSeen(UUID.randomUUID(), "LockBeta");
        seed(alpha, 100L, "seed-lock-alpha");
        seed(beta, 100L, "seed-lock-beta");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<EconomyTransferResult> alphaToBeta = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return economy.transfer(new EconomyTransfer(
                        alpha.playerId(),
                        beta.playerId(),
                        "COINS",
                        10L,
                        "market.trade",
                        "trade-lock-alpha-beta",
                        UUID.randomUUID(),
                        "{}"
                ));
            });
            Future<EconomyTransferResult> betaToAlpha = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return economy.transfer(new EconomyTransfer(
                        beta.playerId(),
                        alpha.playerId(),
                        "COINS",
                        20L,
                        "market.trade",
                        "trade-lock-beta-alpha",
                        UUID.randomUUID(),
                        "{}"
                ));
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<EconomyTransferResult> results = List.of(
                    alphaToBeta.get(10, TimeUnit.SECONDS),
                    betaToAlpha.get(10, TimeUnit.SECONDS)
            );
            assertTrue(results.stream().noneMatch(EconomyTransferResult::idempotentReplay));
        }

        assertEquals(110L, economy.balance(alpha.playerId(), "COINS"));
        assertEquals(90L, economy.balance(beta.playerId(), "COINS"));
        assertEquals(6L, countRows("economy_transaction"));
        assertEquals(8L, countRows("audit_event"));
    }

    @Test
    void oppositeDirectionAccountCreationUsesStableLockOrder() throws Exception {
        PlayerIdentity alpha = identities.recordSeen(UUID.randomUUID(), "CreateAlpha");
        PlayerIdentity beta = identities.recordSeen(UUID.randomUUID(), "CreateBeta");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<EconomyException> alphaToBeta = executor.submit(() -> failedTransferAfterStart(
                    ready,
                    start,
                    new EconomyTransfer(
                            alpha.playerId(), beta.playerId(), "COINS", 1L,
                            "market.trade", "trade-create-alpha-beta", UUID.randomUUID(), "{}"
                    )
            ));
            Future<EconomyException> betaToAlpha = executor.submit(() -> failedTransferAfterStart(
                    ready,
                    start,
                    new EconomyTransfer(
                            beta.playerId(), alpha.playerId(), "COINS", 1L,
                            "market.trade", "trade-create-beta-alpha", UUID.randomUUID(), "{}"
                    )
            ));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(EconomyException.Code.INSUFFICIENT_FUNDS, alphaToBeta.get(10, TimeUnit.SECONDS).code());
            assertEquals(EconomyException.Code.INSUFFICIENT_FUNDS, betaToAlpha.get(10, TimeUnit.SECONDS).code());
        }

        assertEquals(0L, countRows("economy_account"));
        assertEquals(0L, countRows("economy_transaction"));
        assertEquals(0L, countRows("audit_event"));
    }

    @Test
    void composedTransferRequiresCallerOwnedTransaction() throws Exception {
        PlayerIdentity from = identities.recordSeen(UUID.randomUUID(), "AutoFrom");
        PlayerIdentity to = identities.recordSeen(UUID.randomUUID(), "AutoTo");
        try (var connection = database.connection()) {
            assertTrue(connection.getAutoCommit());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> economy.transferInTransaction(connection, new EconomyTransfer(
                            from.playerId(),
                            to.playerId(),
                            "COINS",
                            1L,
                            "market.trade",
                            "trade-autocommit-1",
                            UUID.randomUUID(),
                            "{}"
                    ))
            );
        }
        assertEquals(0L, countRows("economy_transaction"));
        assertEquals(0L, countRows("audit_event"));
    }

    private static EconomyException failedTransferAfterStart(
            CountDownLatch ready,
            CountDownLatch start,
            EconomyTransfer transfer
    ) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            economy.transfer(transfer);
            throw new AssertionError("Expected transfer to fail for zero-balance payer");
        } catch (EconomyException exception) {
            return exception;
        }
    }

    private static void seed(PlayerIdentity player, long amount, String key) {
        economy.apply(new EconomyMutation(
                player.playerId(),
                "COINS",
                amount,
                "test.seed",
                key,
                UUID.randomUUID(),
                "{}"
        ));
    }

    private static long countRows(String table) throws Exception {
        try (var connection = database.connection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void assertTransferAudit(
            UUID fromPlayerId,
            UUID toPlayerId,
            UUID correlationId,
            long amount
    ) throws Exception {
        try (var connection = database.connection();
             var statement = connection.prepareStatement("""
                     SELECT actor_player_id, correlation_id,
                            payload ->> 'toPlayerId' AS to_player_id,
                            (payload ->> 'amount')::BIGINT AS amount
                     FROM audit_event
                     WHERE event_type = 'economy.transfer'
                     LIMIT 1
                     """)) {
            try (var result = statement.executeQuery()) {
                assertTrue(result.next(), "Expected an economy.transfer audit event");
                assertEquals(fromPlayerId, result.getObject("actor_player_id", UUID.class));
                assertEquals(correlationId, result.getObject("correlation_id", UUID.class));
                assertEquals(toPlayerId.toString(), result.getString("to_player_id"));
                assertEquals(amount, result.getLong("amount"));
            }
        }
    }
}
