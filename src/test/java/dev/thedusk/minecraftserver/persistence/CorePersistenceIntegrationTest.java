package dev.thedusk.minecraftserver.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.economy.EconomyException;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.economy.EconomyMutation;
import dev.thedusk.minecraftserver.economy.EconomyResult;
import dev.thedusk.minecraftserver.identity.PlayerIdentity;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import java.sql.SQLException;
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
class CorePersistenceIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_test")
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
                4
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

    private static long countRows(String table) throws SQLException {
        try (var connection = database.connection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }
}
