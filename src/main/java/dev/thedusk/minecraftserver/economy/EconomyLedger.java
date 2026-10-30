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

    private static final String LOCK_TRANSFER_ACCOUNTS_SQL = """
            SELECT account_id, player_id, balance
            FROM economy_account
            WHERE currency_code = ?
              AND player_id IN (?, ?)
            ORDER BY player_id
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
     * milestone atomic. The caller must disable auto-commit before invoking this method.
     */
    public EconomyResult applyInTransaction(Connection connection, EconomyMutation mutation) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(mutation, "mutation");

        try {
            requireCallerOwnedTransaction(connection, "applyInTransaction");
            ensureAccount(connection, mutation.playerId(), mutation.currencyCode());
            Account account = lockAccount(connection, mutation.playerId(), mutation.currencyCode());
            EconomyResult existing = findExisting(connection, mutation, account.accountId());
            if (existing != null) {
                return existing;
            }

            long newBalance = checkedBalanceAfter(account.balance(), mutation.delta(), mutation.currencyCode());
            return recordMutation(connection, account, mutation, newBalance);
        } catch (SQLException exception) {
            throw translateSqlFailure("Failed to apply economy mutation", exception);
        }
    }

    public EconomyTransferResult transfer(EconomyTransfer transfer) {
        Objects.requireNonNull(transfer, "transfer");

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                EconomyTransferResult result = transferInTransaction(connection, transfer);
                connection.commit();
                return result;
            } catch (EconomyException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to commit economy transfer", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire economy database connection", exception);
        }
    }

    /**
     * Transfers currency between two player accounts in a transaction owned by the caller. Both account rows
     * are locked in deterministic player UUID order so opposite-direction transfers cannot create a lock-order
     * cycle. The debit, credit and audit entries are committed or rolled back together by the caller.
     */
    public EconomyTransferResult transferInTransaction(Connection connection, EconomyTransfer transfer) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(transfer, "transfer");

        try {
            requireCallerOwnedTransaction(connection, "transferInTransaction");
            ensureAccount(connection, transfer.fromPlayerId(), transfer.currencyCode());
            ensureAccount(connection, transfer.toPlayerId(), transfer.currencyCode());
            TransferAccounts accounts = lockTransferAccounts(connection, transfer);

            EconomyMutation debit = new EconomyMutation(
                    transfer.fromPlayerId(),
                    transfer.currencyCode(),
                    -transfer.amount(),
                    transfer.reason(),
                    transfer.debitIdempotencyKey(),
                    transfer.correlationId(),
                    transfer.metadataJson()
            );
            EconomyMutation credit = new EconomyMutation(
                    transfer.toPlayerId(),
                    transfer.currencyCode(),
                    transfer.amount(),
                    transfer.reason(),
                    transfer.creditIdempotencyKey(),
                    transfer.correlationId(),
                    transfer.metadataJson()
            );

            EconomyResult existingDebit = findExisting(
                    connection,
                    debit,
                    accounts.from().accountId()
            );
            EconomyResult existingCredit = findExisting(
                    connection,
                    credit,
                    accounts.to().accountId()
            );
            if (existingDebit != null || existingCredit != null) {
                if (existingDebit == null || existingCredit == null
                        || !existingDebit.correlationId().equals(existingCredit.correlationId())) {
                    throw new EconomyException(
                            EconomyException.Code.IDEMPOTENCY_CONFLICT,
                            "Economy transfer idempotency key resolved to an incomplete or inconsistent transfer"
                    );
                }
                return new EconomyTransferResult(
                        existingDebit.transactionId(),
                        existingCredit.transactionId(),
                        transfer.fromPlayerId(),
                        transfer.toPlayerId(),
                        transfer.currencyCode(),
                        transfer.amount(),
                        existingDebit.balanceAfter(),
                        existingCredit.balanceAfter(),
                        existingDebit.correlationId(),
                        true
                );
            }

            long fromBalanceAfter = checkedBalanceAfter(
                    accounts.from().balance(),
                    -transfer.amount(),
                    transfer.currencyCode()
            );
            long toBalanceAfter = checkedBalanceAfter(
                    accounts.to().balance(),
                    transfer.amount(),
                    transfer.currencyCode()
            );

            EconomyResult debitResult = recordMutation(
                    connection,
                    accounts.from(),
                    debit,
                    fromBalanceAfter
            );
            EconomyResult creditResult = recordMutation(
                    connection,
                    accounts.to(),
                    credit,
                    toBalanceAfter
            );
            auditLog.append(
                    connection,
                    transfer.fromPlayerId(),
                    "economy.transfer",
                    transfer.correlationId(),
                    "transfer:" + transfer.idempotencyKey(),
                    transferAuditPayload(transfer, debitResult, creditResult)
            );

            return new EconomyTransferResult(
                    debitResult.transactionId(),
                    creditResult.transactionId(),
                    transfer.fromPlayerId(),
                    transfer.toPlayerId(),
                    transfer.currencyCode(),
                    transfer.amount(),
                    debitResult.balanceAfter(),
                    creditResult.balanceAfter(),
                    transfer.correlationId(),
                    false
            );
        } catch (SQLException exception) {
            throw translateSqlFailure("Failed to apply economy transfer", exception);
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

    private void ensureAccount(Connection connection, UUID playerId, String currencyCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CREATE_ACCOUNT_SQL)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, playerId);
            statement.setString(3, currencyCode);
            statement.executeUpdate();
        }
    }

    private Account lockAccount(Connection connection, UUID playerId, String currencyCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ACCOUNT_SQL)) {
            statement.setObject(1, playerId);
            statement.setString(2, currencyCode);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Economy account disappeared after upsert");
                }
                return new Account(
                        result.getObject("account_id", UUID.class),
                        playerId,
                        result.getLong("balance")
                );
            }
        }
    }

    private TransferAccounts lockTransferAccounts(Connection connection, EconomyTransfer transfer)
            throws SQLException {
        Account from = null;
        Account to = null;
        try (PreparedStatement statement = connection.prepareStatement(LOCK_TRANSFER_ACCOUNTS_SQL)) {
            statement.setString(1, transfer.currencyCode());
            statement.setObject(2, transfer.fromPlayerId());
            statement.setObject(3, transfer.toPlayerId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID playerId = result.getObject("player_id", UUID.class);
                    Account account = new Account(
                            result.getObject("account_id", UUID.class),
                            playerId,
                            result.getLong("balance")
                    );
                    if (playerId.equals(transfer.fromPlayerId())) {
                        from = account;
                    } else if (playerId.equals(transfer.toPlayerId())) {
                        to = account;
                    }
                }
            }
        }
        if (from == null || to == null) {
            throw new SQLException("Economy transfer accounts disappeared after upsert");
        }
        return new TransferAccounts(from, to);
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

    private EconomyResult recordMutation(
            Connection connection,
            Account account,
            EconomyMutation mutation,
            long newBalance
    ) throws SQLException {
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
    }

    private static long checkedBalanceAfter(long currentBalance, long delta, String currencyCode) {
        long newBalance;
        try {
            newBalance = Math.addExact(currentBalance, delta);
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
                    "Insufficient funds for " + currencyCode
            );
        }
        return newBalance;
    }

    private static void requireCallerOwnedTransaction(Connection connection, String operation)
            throws SQLException {
        if (connection.getAutoCommit()) {
            throw new IllegalArgumentException(
                    operation + " requires a caller-owned transaction with autoCommit=false"
            );
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

    private static String transferAuditPayload(
            EconomyTransfer transfer,
            EconomyResult debit,
            EconomyResult credit
    ) {
        return "{\"fromPlayerId\":\"" + transfer.fromPlayerId()
                + "\",\"toPlayerId\":\"" + transfer.toPlayerId()
                + "\",\"currency\":\"" + transfer.currencyCode()
                + "\",\"amount\":" + transfer.amount()
                + ",\"debitTransactionId\":\"" + debit.transactionId()
                + "\",\"creditTransactionId\":\"" + credit.transactionId()
                + "\",\"fromBalanceAfter\":" + debit.balanceAfter()
                + ",\"toBalanceAfter\":" + credit.balanceAfter() + "}";
    }

    private static EconomyException translateSqlFailure(String message, SQLException exception) {
        if ("23505".equals(exception.getSQLState())) {
            return new EconomyException(
                    EconomyException.Code.IDEMPOTENCY_CONFLICT,
                    "Economy idempotency key already belongs to another operation",
                    exception
            );
        }
        return storageFailure(message, exception);
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

    private record Account(UUID accountId, UUID playerId, long balance) {
    }

    private record TransferAccounts(Account from, Account to) {
    }
}
