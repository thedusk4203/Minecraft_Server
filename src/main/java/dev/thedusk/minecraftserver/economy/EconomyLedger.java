package dev.thedusk.minecraftserver.economy;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

public final class EconomyLedger {
    private static final String CREATE_ACCOUNT_SQL = """
            INSERT INTO economy_account (account_id, player_id, currency_code, balance, version)
            VALUES (?, ?, ?, 0, 0)
            ON CONFLICT (player_id, currency_code) DO NOTHING
            """;

    private static final String LOCK_ACCOUNT_SQL = """
            SELECT account_id, balance
            FROM economy_account
            WHERE player_id = ? AND currency_code = ?
            FOR UPDATE
            """;

    private static final String FIND_TRANSACTION_SQL = """
            SELECT transaction_id, account_id, delta, balance_after, reason, correlation_id
            FROM economy_transaction
            WHERE idempotency_key = ?
            """;

    private static final String UPDATE_ACCOUNT_SQL = """
            UPDATE economy_account
            SET balance = ?, version = version + 1, updated_at = NOW()
            WHERE account_id = ?
            """;

    private static final String INSERT_TRANSACTION_SQL = """
            INSERT INTO economy_transaction (
                transaction_id, account_id, idempotency_key, delta, balance_after,
                reason, correlation_id, metadata, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), NOW())
            """;

    private static final String READ_BALANCE_SQL = """
            SELECT balance
            FROM economy_account
            WHERE player_id = ? AND currency_code = ?
            """;

    private final DatabaseManager database;
    private final AuditLog auditLog;

    public EconomyLedger(DatabaseManager database, AuditLog auditLog) {
        this.database = Objects.requireNonNull(database, "database");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    }

    public EconomyResult apply(EconomyMutation mutation) {
        Objects.requireNonNull(mutation, "mutation");

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                EconomyResult result = applyInTransaction(connection, mutation);
                connection.commit();
                return result;
            } catch (EconomyException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to commit economy mutation", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire economy database connection", exception);
        }
    }

    /**
     * Applies a mutation using a transaction owned by the caller. This method never commits or rolls back
     * the supplied connection, allowing a domain workflow to make the ledger entry and its related durable
     * milestone atomic.
     */
    public EconomyResult applyInTransaction(Connection connection, EconomyMutation mutation) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(mutation, "mutation");

        try {
            Account account = lockOrCreateAccount(connection, mutation);
            EconomyResult existing = findExisting(connection, mutation, account.accountId());
            if (existing != null) {
                return existing;
            }

            long newBalance;
            try {
                newBalance = Math.addExact(account.balance(), mutation.delta());
            } catch (ArithmeticException exception) {
                throw new EconomyException(
                        EconomyException.Code.INVALID_MUTATION,
                        "Economy balance overflow",
                        exception
                );
            }

            if (newBalance < 0) {
                throw new EconomyException(
                        EconomyException.Code.INSUFFICIENT_FUNDS,
                        "Insufficient funds for " + mutation.currencyCode()
                );
            }

            UUID transactionId = UUID.randomUUID();
            updateAccount(connection, account.accountId(), newBalance);
            insertTransaction(connection, transactionId, account.accountId(), mutation, newBalance);
            auditLog.append(
                    connection,
                    mutation.playerId(),
                    "economy.balance_changed",
                    mutation.correlationId(),
                    "economy:" + mutation.idempotencyKey(),
                    auditPayload(transactionId, mutation, newBalance)
            );

            return new EconomyResult(
                    transactionId,
                    account.accountId(),
                    mutation.delta(),
                    newBalance,
                    mutation.reason(),
                    mutation.correlationId(),
                    false
            );
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new EconomyException(
                        EconomyException.Code.IDEMPOTENCY_CONFLICT,
                        "Economy idempotency key already belongs to another mutation",
                        exception
                );
            }
            throw storageFailure("Failed to apply economy mutation", exception);
        }
    }

    public long balance(UUID playerId, String currencyCode) {
        Objects.requireNonNull(playerId, "playerId");
        String normalizedCurrency = EconomyMutation.normalizeCurrencyCode(currencyCode);

        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(READ_BALANCE_SQL)) {
            statement.setObject(1, playerId);
            statement.setString(2, normalizedCurrency);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong("balance") : 0L;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to read economy balance", exception);
        }
    }

    private Account lockOrCreateAccount(Connection connection, EconomyMutation mutation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CREATE_ACCOUNT_SQL)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, mutation.playerId());
            statement.setString(3, mutation.currencyCode());
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement(LOCK_ACCOUNT_SQL)) {
            statement.setObject(1, mutation.playerId());
            statement.setString(2, mutation.currencyCode());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Economy account disappeared after upsert");
                }
                return new Account(
                        result.getObject("account_id", UUID.class),
                        result.getLong("balance")
                );
            }
        }
    }

    private EconomyResult findExisting(
            Connection connection,
            EconomyMutation mutation,
            UUID expectedAccountId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_TRANSACTION_SQL)) {
            statement.setString(1, mutation.idempotencyKey());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }

                UUID accountId = result.getObject("account_id", UUID.class);
                long delta = result.getLong("delta");
                String reason = result.getString("reason");
                if (!expectedAccountId.equals(accountId)
                        || delta != mutation.delta()
                        || !mutation.reason().equals(reason)) {
                    throw new EconomyException(
                            EconomyException.Code.IDEMPOTENCY_CONFLICT,
                            "Economy idempotency key was reused with different semantics"
                    );
                }

                return new EconomyResult(
                        result.getObject("transaction_id", UUID.class),
                        accountId,
                        delta,
                        result.getLong("balance_after"),
                        reason,
                        result.getObject("correlation_id", UUID.class),
                        true
                );
            }
        }
    }

    private static void updateAccount(Connection connection, UUID accountId, long newBalance)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_ACCOUNT_SQL)) {
            statement.setLong(1, newBalance);
            statement.setObject(2, accountId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Economy account update affected an unexpected row count");
            }
        }
    }

    private static void insertTransaction(
            Connection connection,
            UUID transactionId,
            UUID accountId,
            EconomyMutation mutation,
            long newBalance
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TRANSACTION_SQL)) {
            statement.setObject(1, transactionId);
            statement.setObject(2, accountId);
            statement.setString(3, mutation.idempotencyKey());
            statement.setLong(4, mutation.delta());
            statement.setLong(5, newBalance);
            statement.setString(6, mutation.reason());
            statement.setObject(7, mutation.correlationId());
            statement.setString(8, mutation.metadataJson());
            statement.executeUpdate();
        }
    }

    private static String auditPayload(UUID transactionId, EconomyMutation mutation, long newBalance) {
        return "{\"transactionId\":\"" + transactionId
                + "\",\"currency\":\"" + mutation.currencyCode()
                + "\",\"delta\":" + mutation.delta()
                + ",\"balanceAfter\":" + newBalance + "}";
    }

    private static EconomyException storageFailure(String message, SQLException exception) {
        return new EconomyException(EconomyException.Code.STORAGE_FAILURE, message, exception);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original exception is more useful than a rollback failure here.
        }
    }

    private record Account(UUID accountId, long balance) {
    }
}
