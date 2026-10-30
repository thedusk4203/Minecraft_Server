package dev.thedusk.minecraftserver.identity;

import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.PersistenceException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerIdentityRepository {
    private static final String UPSERT_SEEN_SQL = """
            INSERT INTO player_identity (
                player_id, minecraft_uuid, last_known_name, platform, platform_subject,
                created_at, updated_at, last_seen_at
            )
            VALUES (?, ?, ?, ?, ?, NOW(), NOW(), NOW())
            ON CONFLICT (minecraft_uuid) DO UPDATE SET
                last_known_name = EXCLUDED.last_known_name,
                updated_at = NOW(),
                last_seen_at = NOW()
            RETURNING player_id, minecraft_uuid, last_known_name, platform, platform_subject
            """;

    private static final String FIND_BY_MINECRAFT_UUID_SQL = """
            SELECT player_id, minecraft_uuid, last_known_name, platform, platform_subject
            FROM player_identity
            WHERE minecraft_uuid = ?
            """;

    private final DatabaseManager database;

    public PlayerIdentityRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public PlayerIdentity recordSeen(UUID minecraftUuid, String lastKnownName) {
        return recordSeen(minecraftUuid, lastKnownName, IdentityPlatform.UNKNOWN, null);
    }

    public PlayerIdentity recordSeen(
            UUID minecraftUuid,
            String lastKnownName,
            IdentityPlatform initialPlatform,
            String initialPlatformSubject
    ) {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        Objects.requireNonNull(initialPlatform, "initialPlatform");
        String normalizedName = normalizeName(lastKnownName);

        try (var connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_SEEN_SQL)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, minecraftUuid);
            statement.setString(3, normalizedName);
            statement.setString(4, initialPlatform.name());
            statement.setString(5, normalizeSubject(initialPlatformSubject));

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("player identity upsert returned no row");
                }
                return map(result);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Failed to persist player identity " + minecraftUuid, exception);
        }
    }

    public Optional<PlayerIdentity> findByMinecraftUuid(UUID minecraftUuid) {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");

        try (var connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(FIND_BY_MINECRAFT_UUID_SQL)) {
            statement.setObject(1, minecraftUuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Failed to read player identity " + minecraftUuid, exception);
        }
    }

    private static PlayerIdentity map(ResultSet result) throws SQLException {
        return new PlayerIdentity(
                result.getObject("player_id", UUID.class),
                result.getObject("minecraft_uuid", UUID.class),
                result.getString("last_known_name"),
                IdentityPlatform.valueOf(result.getString("platform")),
                result.getString("platform_subject")
        );
    }

    private static String normalizeName(String value) {
        Objects.requireNonNull(value, "lastKnownName");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 16) {
            throw new IllegalArgumentException("lastKnownName must contain 1-16 characters");
        }
        return normalized;
    }

    private static String normalizeSubject(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("platformSubject must be at most 128 characters");
        }
        return normalized;
    }
}
