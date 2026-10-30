package dev.thedusk.minecraftserver.onboarding;

import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.PersistenceException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class OnboardingProgressRepository {
    private static final String ENSURE_STARTED_SQL = """
            INSERT INTO player_onboarding (player_id, started_at, updated_at)
            VALUES (?, NOW(), NOW())
            ON CONFLICT (player_id) DO NOTHING
            """;

    private static final String MARK_LAND_CLAIMED_SQL = """
            UPDATE player_onboarding
            SET land_claimed_at = NOW(), updated_at = NOW()
            WHERE player_id = ?
              AND land_claimed_at IS NULL
            """;

    private static final String FIND_SQL = """
            SELECT player_id, started_at, land_claimed_at, first_currency_earned_at,
                   market_used_at, completed_at
            FROM player_onboarding
            WHERE player_id = ?
            """;

    private final DatabaseManager database;

    public OnboardingProgressRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public boolean ensureStarted(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try (var connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(ENSURE_STARTED_SQL)) {
            statement.setObject(1, playerId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new PersistenceException("Failed to start onboarding for player " + playerId, exception);
        }
    }

    public boolean markLandClaimed(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try (var connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                ensureStarted(connection, playerId);
                boolean changed;
                try (PreparedStatement statement = connection.prepareStatement(MARK_LAND_CLAIMED_SQL)) {
                    statement.setObject(1, playerId);
                    changed = statement.executeUpdate() == 1;
                }
                connection.commit();
                return changed;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Failed to record land-claim milestone for player " + playerId, exception);
        }
    }

    public Optional<OnboardingProgress> find(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try (var connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setObject(1, playerId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Failed to read onboarding progress for player " + playerId, exception);
        }
    }

    private static void ensureStarted(java.sql.Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ENSURE_STARTED_SQL)) {
            statement.setObject(1, playerId);
            statement.executeUpdate();
        }
    }

    private static OnboardingProgress map(ResultSet result) throws SQLException {
        return new OnboardingProgress(
                result.getObject("player_id", UUID.class),
                instant(result, "started_at"),
                instant(result, "land_claimed_at"),
                instant(result, "first_currency_earned_at"),
                instant(result, "market_used_at"),
                instant(result, "completed_at")
        );
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void rollback(java.sql.Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
