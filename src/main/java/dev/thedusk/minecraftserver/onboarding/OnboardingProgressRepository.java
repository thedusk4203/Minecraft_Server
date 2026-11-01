package dev.thedusk.minecraftserver.onboarding;

import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.PersistenceException;
import java.sql.Connection;
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

    private static final String MARK_FIRST_SKILL_SQL = """
            UPDATE player_onboarding
            SET first_skill_level_at = NOW(),
                first_skill_id = ?,
                first_skill_level = ?,
                updated_at = NOW()
            WHERE player_id = ?
              AND first_skill_level_at IS NULL
            """;

    private static final String MARK_FIRST_CURRENCY_SQL = """
            UPDATE player_onboarding
            SET first_currency_earned_at = NOW(), updated_at = NOW()
            WHERE player_id = ?
              AND first_currency_earned_at IS NULL
            """;

    private static final String MARK_MARKET_USED_SQL = """
            UPDATE player_onboarding
            SET market_used_at = NOW(), updated_at = NOW()
            WHERE player_id = ?
              AND market_used_at IS NULL
            """;

    private static final String FIND_SQL = """
            SELECT player_id, started_at, land_claimed_at,
                   first_skill_level_at, first_skill_id, first_skill_level,
                   first_currency_earned_at, market_used_at, completed_at
            FROM player_onboarding
            WHERE player_id = ?
            """;

    private static final String LOCK_SQL = FIND_SQL + " FOR UPDATE";

    private final DatabaseManager database;

    public OnboardingProgressRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public boolean ensureStarted(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = database.connection()) {
            return ensureStarted(connection, playerId);
        } catch (SQLException exception) {
            throw new PersistenceException("Failed to start onboarding for player " + playerId, exception);
        }
    }

    public boolean markLandClaimed(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = database.connection()) {
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
        try (Connection connection = database.connection()) {
            return find(connection, playerId);
        } catch (SQLException exception) {
            throw new PersistenceException("Failed to read onboarding progress for player " + playerId, exception);
        }
    }

    boolean ensureStarted(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ENSURE_STARTED_SQL)) {
            statement.setObject(1, playerId);
            return statement.executeUpdate() == 1;
        }
    }

    OnboardingProgress lock(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL)) {
            statement.setObject(1, playerId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Onboarding row missing for player " + playerId);
                }
                return map(result);
            }
        }
    }

    boolean markFirstSkill(Connection connection, UUID playerId, String skillId, int level)
            throws SQLException {
        Objects.requireNonNull(skillId, "skillId");
        if (skillId.isBlank() || skillId.length() > 128) {
            throw new IllegalArgumentException("skillId must contain 1-128 characters");
        }
        if (level < 1) {
            throw new IllegalArgumentException("level must be at least 1");
        }

        try (PreparedStatement statement = connection.prepareStatement(MARK_FIRST_SKILL_SQL)) {
            statement.setString(1, skillId);
            statement.setInt(2, level);
            statement.setObject(3, playerId);
            return statement.executeUpdate() == 1;
        }
    }

    boolean markFirstCurrencyEarned(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MARK_FIRST_CURRENCY_SQL)) {
            statement.setObject(1, playerId);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean markMarketUsedInTransaction(Connection connection, UUID playerId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(playerId, "playerId");
        if (connection.getAutoCommit()) {
            throw new IllegalArgumentException(
                    "markMarketUsedInTransaction requires a caller-owned transaction with autoCommit=false"
            );
        }
        ensureStarted(connection, playerId);
        try (PreparedStatement statement = connection.prepareStatement(MARK_MARKET_USED_SQL)) {
            statement.setObject(1, playerId);
            return statement.executeUpdate() == 1;
        }
    }

    private Optional<OnboardingProgress> find(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setObject(1, playerId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private static OnboardingProgress map(ResultSet result) throws SQLException {
        Integer firstSkillLevel = result.getObject("first_skill_level", Integer.class);
        return new OnboardingProgress(
                result.getObject("player_id", UUID.class),
                instant(result, "started_at"),
                instant(result, "land_claimed_at"),
                instant(result, "first_skill_level_at"),
                result.getString("first_skill_id"),
                firstSkillLevel,
                instant(result, "first_currency_earned_at"),
                instant(result, "market_used_at"),
                instant(result, "completed_at")
        );
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
