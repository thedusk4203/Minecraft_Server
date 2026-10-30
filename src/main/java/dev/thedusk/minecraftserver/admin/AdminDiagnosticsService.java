package dev.thedusk.minecraftserver.admin;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class AdminDiagnosticsService {
    private static final String SNAPSHOT_SQL = """
            SELECT
                (SELECT COUNT(*) FROM player_identity) AS known_players,
                (SELECT COUNT(*) FROM economy_account) AS economy_accounts,
                (SELECT COUNT(*) FROM economy_transaction) AS economy_transactions,
                (SELECT COUNT(*) FROM audit_event) AS audit_events
            """;

    private static final String ACTOR_SQL = """
            SELECT player_id
            FROM player_identity
            WHERE minecraft_uuid = ?
            """;

    private final DatabaseManager database;
    private final AuditLog auditLog;

    public AdminDiagnosticsService(DatabaseManager database, AuditLog auditLog) {
        this.database = Objects.requireNonNull(database, "database");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    }

    public AdminDiagnosticsSnapshot collect(
            UUID actorMinecraftUuid,
            UUID correlationId
    ) {
        Objects.requireNonNull(correlationId, "correlationId");
        Counts counts;

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                UUID actorPlayerId = findActorPlayerId(connection, actorMinecraftUuid);
                counts = readCounts(connection);
                appendAdminAudit(connection, actorPlayerId, "diagnostics", correlationId);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Failed to collect administrative diagnostics", exception);
        }

        return new AdminDiagnosticsSnapshot(
                counts.knownPlayers(),
                counts.economyAccounts(),
                counts.economyTransactions(),
                counts.auditEvents(),
                database.activeConnections(),
                database.idleConnections()
        );
    }

    public void auditCommand(
            UUID actorMinecraftUuid,
            String subcommand,
            UUID correlationId
    ) {
        Objects.requireNonNull(correlationId, "correlationId");
        String normalizedSubcommand = normalizeSubcommand(subcommand);

        try (Connection connection = database.connection()) {
            UUID actorPlayerId = findActorPlayerId(connection, actorMinecraftUuid);
            appendAdminAudit(connection, actorPlayerId, normalizedSubcommand, correlationId);
        } catch (SQLException exception) {
            throw new PersistenceException("Failed to audit administrative command", exception);
        }
    }

    private void appendAdminAudit(
            Connection connection,
            UUID actorPlayerId,
            String subcommand,
            UUID correlationId
    ) throws SQLException {
        auditLog.append(
                connection,
                actorPlayerId,
                "admin.command",
                correlationId,
                null,
                "{\"subcommand\":\"" + normalizeSubcommand(subcommand) + "\"}"
        );
    }

    private static Counts readCounts(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SNAPSHOT_SQL);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("diagnostics query returned no row");
            }
            return new Counts(
                    result.getLong("known_players"),
                    result.getLong("economy_accounts"),
                    result.getLong("economy_transactions"),
                    result.getLong("audit_events")
            );
        }
    }

    private static UUID findActorPlayerId(Connection connection, UUID minecraftUuid) throws SQLException {
        if (minecraftUuid == null) {
            return null;
        }

        try (PreparedStatement statement = connection.prepareStatement(ACTOR_SQL)) {
            statement.setObject(1, minecraftUuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getObject("player_id", UUID.class) : null;
            }
        }
    }

    private static String normalizeSubcommand(String value) {
        Objects.requireNonNull(value, "subcommand");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("health") && !normalized.equals("diagnostics")) {
            throw new IllegalArgumentException("Unsupported administrative diagnostics subcommand: " + value);
        }
        return normalized;
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record Counts(
            long knownPlayers,
            long economyAccounts,
            long economyTransactions,
            long auditEvents
    ) {
    }
}
