package dev.thedusk.minecraftserver.reward;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class StoneWardenItemRewardRepository {
    private static final String ELIGIBLE_ALL_SQL = """
            SELECT reward.reward_id, reward.player_id
            FROM encounter_reward reward
            JOIN encounter_run run ON run.run_id = reward.run_id
            WHERE reward.reward_key = ?
              AND reward.reward_version = ?
              AND reward.status = 'CURRENCY_GRANTED'
              AND run.encounter_id = ?
              AND run.status = 'COMPLETED'
            ORDER BY reward.reward_id
            FOR UPDATE OF reward
            """;

    private static final String ELIGIBLE_PLAYER_SQL = """
            SELECT reward.reward_id, reward.player_id
            FROM encounter_reward reward
            JOIN encounter_run run ON run.run_id = reward.run_id
            WHERE reward.reward_key = ?
              AND reward.reward_version = ?
              AND reward.status = 'CURRENCY_GRANTED'
              AND run.encounter_id = ?
              AND run.status = 'COMPLETED'
              AND reward.player_id = ?
            ORDER BY reward.reward_id
            FOR UPDATE OF reward
            """;

    private static final String INSERT_ITEM_SQL = """
            INSERT INTO encounter_reward_item (
                reward_id, item_key, item_version, delivery_token, status,
                item_material, item_amount, item_payload, item_sha256,
                created_at, snapshot_prepared_at, delivered_at, updated_at, version
            )
            VALUES (?, ?, ?, ?, 'PREPARED', ?, ?, NULL, NULL, NOW(), NULL, NULL, NOW(), 0)
            ON CONFLICT (reward_id) DO NOTHING
            RETURNING reward_id
            """;

    private static final String LOCK_ITEM_SQL = """
            SELECT item.reward_id, reward.player_id, item.item_key, item.item_version,
                   item.delivery_token, item.status, item.item_material, item.item_amount,
                   item.item_payload, item.item_sha256
            FROM encounter_reward_item item
            JOIN encounter_reward reward ON reward.reward_id = item.reward_id
            WHERE item.reward_id = ?
            FOR UPDATE OF item
            """;

    private static final String FIND_BY_PLAYER_SQL = """
            SELECT item.reward_id, reward.player_id, item.item_key, item.item_version,
                   item.delivery_token, item.status, item.item_material, item.item_amount,
                   item.item_payload, item.item_sha256
            FROM encounter_reward_item item
            JOIN encounter_reward reward ON reward.reward_id = item.reward_id
            WHERE reward.player_id = ?
            ORDER BY item.created_at, item.reward_id
            """;

    private static final String FIND_BY_TOKEN_SQL = """
            SELECT item.reward_id, reward.player_id, item.item_key, item.item_version,
                   item.delivery_token, item.status, item.item_material, item.item_amount,
                   item.item_payload, item.item_sha256
            FROM encounter_reward_item item
            JOIN encounter_reward reward ON reward.reward_id = item.reward_id
            WHERE item.delivery_token = ?
            """;

    private static final String PREPARE_SNAPSHOT_SQL = """
            UPDATE encounter_reward_item
            SET status = 'DELIVERY_PENDING', item_payload = ?, item_sha256 = ?,
                snapshot_prepared_at = NOW(), updated_at = NOW(), version = version + 1
            WHERE reward_id = ? AND status = 'PREPARED'
            """;

    private static final String MARK_DELIVERED_SQL = """
            UPDATE encounter_reward_item
            SET status = 'DELIVERED', delivered_at = NOW(),
                updated_at = NOW(), version = version + 1
            WHERE reward_id = ? AND status = 'DELIVERY_PENDING'
            """;

    private final DatabaseManager database;
    private final AuditLog auditLog;

    public StoneWardenItemRewardRepository(DatabaseManager database, AuditLog auditLog) {
        this.database = Objects.requireNonNull(database, "database");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    }

    public int prepareEligibleIntents(UUID correlationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        return prepareEligible(null, correlationId);
    }

    public int prepareEligibleIntentsForPlayer(UUID playerId, UUID correlationId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(correlationId, "correlationId");
        return prepareEligible(playerId, correlationId);
    }

    public List<StoneWardenItemReward> findByPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(FIND_BY_PLAYER_SQL)) {
            statement.setObject(1, playerId);
            try (ResultSet result = statement.executeQuery()) {
                List<StoneWardenItemReward> rows = new ArrayList<>();
                while (result.next()) {
                    StoneWardenItemReward row = map(result);
                    validateSemantics(row);
                    rows.add(row);
                }
                return List.copyOf(rows);
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to query Stone Warden item rewards for player", exception);
        }
    }

    public Optional<StoneWardenItemReward> findByToken(UUID deliveryToken) {
        Objects.requireNonNull(deliveryToken, "deliveryToken");
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(FIND_BY_TOKEN_SQL)) {
            statement.setObject(1, deliveryToken);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                StoneWardenItemReward row = map(result);
                validateSemantics(row);
                if (result.next()) {
                    throw semanticConflict("Duplicate Stone Warden item delivery token: " + deliveryToken);
                }
                return Optional.of(row);
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to query Stone Warden item reward token", exception);
        }
    }

    public StoneWardenItemReward prepareSnapshot(
            UUID rewardId,
            byte[] payload,
            String sha256,
            UUID correlationId
    ) {
        Objects.requireNonNull(rewardId, "rewardId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(correlationId, "correlationId");
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
        }

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                StoneWardenItemReward current = requireLocked(connection, rewardId);
                validateSemantics(current);
                if (current.status() != StoneWardenItemRewardStatus.PREPARED) {
                    connection.commit();
                    return current;
                }

                try (PreparedStatement statement = connection.prepareStatement(PREPARE_SNAPSHOT_SQL)) {
                    statement.setBytes(1, Arrays.copyOf(payload, payload.length));
                    statement.setString(2, sha256);
                    statement.setObject(3, rewardId);
                    if (statement.executeUpdate() != 1) {
                        throw semanticConflict("Stone Warden item reward left PREPARED before snapshot persistence");
                    }
                }

                auditLog.append(
                        connection,
                        current.playerId(),
                        "encounter.reward_item_snapshot_prepared",
                        correlationId,
                        snapshotAuditKey(rewardId),
                        payload(current, "DELIVERY_PENDING")
                );

                StoneWardenItemReward prepared = requireLocked(connection, rewardId);
                connection.commit();
                return prepared;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to prepare Stone Warden item reward snapshot", exception);
        }
    }

    public StoneWardenItemReward markDelivered(UUID rewardId, UUID correlationId) {
        Objects.requireNonNull(rewardId, "rewardId");
        Objects.requireNonNull(correlationId, "correlationId");

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                StoneWardenItemReward current = requireLocked(connection, rewardId);
                validateSemantics(current);
                if (current.status() == StoneWardenItemRewardStatus.DELIVERED) {
                    connection.commit();
                    return current;
                }
                if (current.status() != StoneWardenItemRewardStatus.DELIVERY_PENDING) {
                    throw invalidState("Stone Warden item reward has no persisted snapshot: " + rewardId);
                }

                try (PreparedStatement statement = connection.prepareStatement(MARK_DELIVERED_SQL)) {
                    statement.setObject(1, rewardId);
                    if (statement.executeUpdate() != 1) {
                        throw semanticConflict("Stone Warden item reward left DELIVERY_PENDING before acknowledgement");
                    }
                }

                auditLog.append(
                        connection,
                        current.playerId(),
                        "encounter.reward_item_delivered",
                        correlationId,
                        deliveredAuditKey(rewardId),
                        payload(current, "DELIVERED")
                );

                StoneWardenItemReward delivered = requireLocked(connection, rewardId);
                connection.commit();
                return delivered;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acknowledge Stone Warden item reward delivery", exception);
        }
    }

    private int prepareEligible(UUID playerId, UUID correlationId) {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                List<RewardOwner> eligible = lockEligibleRewards(connection, playerId);
                int inserted = 0;
                for (RewardOwner owner : eligible) {
                    if (insertPrepared(connection, owner.rewardId())) {
                        inserted++;
                        StoneWardenItemReward row = requireLocked(connection, owner.rewardId());
                        auditLog.append(
                                connection,
                                owner.playerId(),
                                "encounter.reward_item_prepared",
                                correlationId,
                                prepareAuditKey(owner.rewardId()),
                                payload(row, "PREPARED")
                        );
                    } else {
                        validateSemantics(requireLocked(connection, owner.rewardId()));
                    }
                }
                connection.commit();
                return inserted;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to prepare eligible Stone Warden item rewards", exception);
        }
    }

    private static List<RewardOwner> lockEligibleRewards(Connection connection, UUID playerId)
            throws SQLException {
        String sql = playerId == null ? ELIGIBLE_ALL_SQL : ELIGIBLE_PLAYER_SQL;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, StoneWardenRewardService.REWARD_KEY);
            statement.setInt(2, StoneWardenRewardService.REWARD_VERSION);
            statement.setString(3, StoneWardenRewardService.ENCOUNTER_ID);
            if (playerId != null) {
                statement.setObject(4, playerId);
            }
            try (ResultSet result = statement.executeQuery()) {
                List<RewardOwner> eligible = new ArrayList<>();
                while (result.next()) {
                    eligible.add(new RewardOwner(
                            result.getObject("reward_id", UUID.class),
                            result.getObject("player_id", UUID.class)
                    ));
                }
                return List.copyOf(eligible);
            }
        }
    }

    private static boolean insertPrepared(Connection connection, UUID rewardId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_ITEM_SQL)) {
            statement.setObject(1, rewardId);
            statement.setString(2, StoneWardenItemRewardDefinition.ITEM_KEY);
            statement.setInt(3, StoneWardenItemRewardDefinition.ITEM_VERSION);
            statement.setObject(4, rewardId);
            statement.setString(5, StoneWardenItemRewardDefinition.MATERIAL);
            statement.setInt(6, StoneWardenItemRewardDefinition.AMOUNT);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static StoneWardenItemReward requireLocked(Connection connection, UUID rewardId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ITEM_SQL)) {
            statement.setObject(1, rewardId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw invalidState("Stone Warden item reward not found: " + rewardId);
                }
                return map(result);
            }
        }
    }

    private static StoneWardenItemReward map(ResultSet result) throws SQLException {
        return new StoneWardenItemReward(
                result.getObject("reward_id", UUID.class),
                result.getObject("player_id", UUID.class),
                result.getString("item_key"),
                result.getInt("item_version"),
                result.getObject("delivery_token", UUID.class),
                StoneWardenItemRewardStatus.valueOf(result.getString("status")),
                result.getString("item_material"),
                result.getInt("item_amount"),
                result.getBytes("item_payload"),
                result.getString("item_sha256")
        );
    }

    private static void validateSemantics(StoneWardenItemReward row) {
        if (!StoneWardenItemRewardDefinition.ITEM_KEY.equals(row.itemKey())
                || row.itemVersion() != StoneWardenItemRewardDefinition.ITEM_VERSION
                || !row.rewardId().equals(row.deliveryToken())
                || !StoneWardenItemRewardDefinition.MATERIAL.equals(row.itemMaterial())
                || row.itemAmount() != StoneWardenItemRewardDefinition.AMOUNT) {
            throw semanticConflict("Stone Warden item reward identity is bound to different semantics");
        }
        if (row.status() == StoneWardenItemRewardStatus.PREPARED) {
            if (row.itemPayload() != null || row.itemSha256() != null) {
                throw semanticConflict("PREPARED Stone Warden item reward unexpectedly contains a snapshot");
            }
        } else if (row.itemPayload() == null || row.itemPayload().length == 0 || row.itemSha256() == null) {
            throw semanticConflict("Persisted Stone Warden item reward snapshot is incomplete");
        }
    }

    private static String prepareAuditKey(UUID rewardId) {
        return "encounter:reward:" + rewardId + ":item-prepared";
    }

    private static String snapshotAuditKey(UUID rewardId) {
        return "encounter:reward:" + rewardId + ":item-snapshot";
    }

    private static String deliveredAuditKey(UUID rewardId) {
        return "encounter:reward:" + rewardId + ":item-delivered";
    }

    private static String payload(StoneWardenItemReward row, String status) {
        return "{\"rewardId\":\"" + row.rewardId()
                + "\",\"playerId\":\"" + row.playerId()
                + "\",\"itemKey\":\"" + StoneWardenItemRewardDefinition.ITEM_KEY
                + "\",\"itemVersion\":" + StoneWardenItemRewardDefinition.ITEM_VERSION
                + ",\"material\":\"" + StoneWardenItemRewardDefinition.MATERIAL
                + "\",\"amount\":" + StoneWardenItemRewardDefinition.AMOUNT
                + ",\"deliveryToken\":\"" + row.deliveryToken()
                + "\",\"status\":\"" + status + "\"}";
    }

    private static EncounterRewardException invalidState(String message) {
        return new EncounterRewardException(EncounterRewardException.Code.INVALID_STATE, message);
    }

    private static EncounterRewardException semanticConflict(String message) {
        return new EncounterRewardException(EncounterRewardException.Code.SEMANTIC_CONFLICT, message);
    }

    private static EncounterRewardException storageFailure(String message, SQLException exception) {
        if ("23505".equals(exception.getSQLState()) || "23514".equals(exception.getSQLState())) {
            return new EncounterRewardException(
                    EncounterRewardException.Code.SEMANTIC_CONFLICT,
                    message,
                    exception
            );
        }
        return new EncounterRewardException(
                EncounterRewardException.Code.STORAGE_FAILURE,
                message,
                exception
        );
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }

    private record RewardOwner(UUID rewardId, UUID playerId) {
    }
}
