package dev.thedusk.minecraftserver.reward;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.economy.EconomyMutation;
import dev.thedusk.minecraftserver.economy.EconomyResult;
import dev.thedusk.minecraftserver.encounter.EncounterRun;
import dev.thedusk.minecraftserver.encounter.EncounterRunStatus;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class StoneWardenRewardService {
    public static final String ENCOUNTER_ID = "stone_warden";
    public static final String REWARD_KEY = "stone_warden_completion";
    public static final int REWARD_VERSION = 1;
    public static final String CURRENCY_CODE = "COINS";
    public static final long CURRENCY_AMOUNT = 250L;

    private static final String LOCK_RUN_SQL = """
            SELECT encounter_id, encounter_version, status
            FROM encounter_run
            WHERE run_id = ?
            FOR UPDATE
            """;

    private static final String FIND_PARTICIPANTS_SQL = """
            SELECT player_id
            FROM encounter_participant
            WHERE run_id = ?
            ORDER BY player_id
            """;

    private static final String INSERT_REWARD_SQL = """
            INSERT INTO encounter_reward (
                reward_id, run_id, player_id, reward_key, reward_version,
                status, currency_code, currency_amount, currency_transaction_id,
                created_at, updated_at, version
            )
            VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, NULL, NOW(), NOW(), 0)
            ON CONFLICT (run_id, player_id, reward_key, reward_version) DO NOTHING
            RETURNING reward_id
            """;

    private static final String FIND_REWARD_BY_IDENTITY_SQL = """
            SELECT reward_id, run_id, player_id, reward_key, reward_version,
                   status, currency_code, currency_amount, currency_transaction_id
            FROM encounter_reward
            WHERE run_id = ? AND player_id = ? AND reward_key = ? AND reward_version = ?
            FOR UPDATE
            """;

    private static final String LOCK_RUN_REWARDS_SQL = """
            SELECT reward_id, run_id, player_id, reward_key, reward_version,
                   status, currency_code, currency_amount, currency_transaction_id
            FROM encounter_reward
            WHERE run_id = ? AND reward_key = ? AND reward_version = ?
            ORDER BY player_id
            FOR UPDATE
            """;

    private static final String MARK_GRANTED_SQL = """
            UPDATE encounter_reward
            SET status = 'CURRENCY_GRANTED', currency_transaction_id = ?,
                updated_at = NOW(), version = version + 1
            WHERE reward_id = ? AND status = 'PENDING'
            """;

    private static final String FIND_RECONCILABLE_RUNS_SQL = """
            SELECT run.run_id
            FROM encounter_run run
            LEFT JOIN encounter_reward reward
              ON reward.run_id = run.run_id
             AND reward.reward_key = ?
             AND reward.reward_version = ?
            WHERE run.encounter_id = ?
              AND run.status = 'COMPLETED'
            GROUP BY run.run_id, run.ended_at
            HAVING COUNT(reward.reward_id) = 0
                OR BOOL_OR(reward.status = 'PENDING')
            ORDER BY run.ended_at, run.run_id
            LIMIT 1000
            """;

    private final DatabaseManager database;
    private final AuditLog auditLog;
    private final EconomyLedger economy;

    public StoneWardenRewardService(DatabaseManager database, AuditLog auditLog, EconomyLedger economy) {
        this.database = Objects.requireNonNull(database, "database");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.economy = Objects.requireNonNull(economy, "economy");
    }

    public int prepare(EncounterRun run, UUID correlationId) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(correlationId, "correlationId");
        if (!ENCOUNTER_ID.equals(run.encounterId())) {
            throw new IllegalArgumentException("Stone Warden rewards only support encounter '" + ENCOUNTER_ID + "'");
        }

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                RunState state = lockRun(connection, run.runId());
                requireRunSemantics(run, state);
                if (state.status() != EncounterRunStatus.ACTIVE) {
                    throw invalidState("Reward intent can only be prepared for an ACTIVE encounter run");
                }

                List<UUID> participants = participants(connection, run.runId());
                if (!participants.equals(run.participantPlayerIds())) {
                    throw semanticConflict("Encounter participant set changed before reward preparation");
                }

                int newlyPrepared = ensureRewardIntents(
                        connection,
                        run.runId(),
                        participants,
                        correlationId
                );
                connection.commit();
                return newlyPrepared;
            } catch (EncounterRewardException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to prepare Stone Warden rewards", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire reward database connection", exception);
        }
    }

    public RewardGrantSummary grantCompleted(UUID runId, UUID correlationId) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(correlationId, "correlationId");

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                RunState state = lockRun(connection, runId);
                if (!ENCOUNTER_ID.equals(state.encounterId())) {
                    throw semanticConflict("Reward run is not a Stone Warden encounter");
                }
                if (state.status() != EncounterRunStatus.COMPLETED) {
                    throw invalidState("Stone Warden rewards require a COMPLETED encounter run");
                }

                List<UUID> participants = participants(connection, runId);
                if (participants.isEmpty()) {
                    throw semanticConflict("Completed reward run has no participants: " + runId);
                }
                ensureRewardIntents(connection, runId, participants, correlationId);

                List<RewardRow> rewards = lockRewards(connection, runId);
                validateRewardCoverage(runId, participants, rewards);

                int newlyGranted = 0;
                int replayed = 0;
                for (RewardRow reward : rewards) {
                    validateRewardSemantics(reward, runId, reward.playerId());
                    if ("CURRENCY_GRANTED".equals(reward.status())) {
                        if (reward.currencyTransactionId() == null) {
                            throw semanticConflict("Granted reward is missing its economy transaction id");
                        }
                        replayed++;
                        continue;
                    }
                    if (!"PENDING".equals(reward.status())) {
                        throw semanticConflict("Unsupported reward status: " + reward.status());
                    }

                    EconomyResult economyResult = economy.applyInTransaction(
                            connection,
                            new EconomyMutation(
                                    reward.playerId(),
                                    CURRENCY_CODE,
                                    CURRENCY_AMOUNT,
                                    "STONE_WARDEN_REWARD",
                                    economyIdempotencyKey(reward.rewardId()),
                                    correlationId,
                                    economyMetadata(runId, reward.rewardId())
                            )
                    );
                    markGranted(connection, reward.rewardId(), economyResult.transactionId());
                    auditLog.append(
                            connection,
                            reward.playerId(),
                            "encounter.reward_currency_granted",
                            correlationId,
                            grantAuditKey(reward.rewardId()),
                            rewardPayload(
                                    reward.rewardId(),
                                    runId,
                                    reward.playerId(),
                                    economyResult.transactionId()
                            )
                    );
                    newlyGranted++;
                }

                connection.commit();
                return new RewardGrantSummary(
                        runId,
                        participants.size(),
                        newlyGranted,
                        replayed,
                        CURRENCY_CODE,
                        CURRENCY_AMOUNT
                );
            } catch (EncounterRewardException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to grant Stone Warden rewards", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire reward database connection", exception);
        }
    }

    public int reconcileCompletedPending() {
        List<UUID> runIds = reconciliableRunIds();
        for (UUID runId : runIds) {
            grantCompleted(runId, UUID.randomUUID());
        }
        return runIds.size();
    }

    private List<UUID> reconciliableRunIds() {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(FIND_RECONCILABLE_RUNS_SQL)) {
            statement.setString(1, REWARD_KEY);
            statement.setInt(2, REWARD_VERSION);
            statement.setString(3, ENCOUNTER_ID);
            try (ResultSet result = statement.executeQuery()) {
                List<UUID> runIds = new ArrayList<>();
                while (result.next()) {
                    runIds.add(result.getObject("run_id", UUID.class));
                }
                return List.copyOf(runIds);
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to query pending completed rewards", exception);
        }
    }

    private int ensureRewardIntents(
            Connection connection,
            UUID runId,
            List<UUID> participants,
            UUID correlationId
    ) throws SQLException {
        int newlyPrepared = 0;
        for (UUID playerId : participants) {
            UUID proposedRewardId = UUID.randomUUID();
            UUID insertedRewardId = insertReward(connection, proposedRewardId, runId, playerId);
            if (insertedRewardId != null) {
                newlyPrepared++;
                auditLog.append(
                        connection,
                        playerId,
                        "encounter.reward_prepared",
                        correlationId,
                        prepareAuditKey(insertedRewardId),
                        rewardPayload(insertedRewardId, runId, playerId, null)
                );
            } else {
                RewardRow existing = requireRewardByIdentity(connection, runId, playerId);
                validateRewardSemantics(existing, runId, playerId);
            }
        }
        return newlyPrepared;
    }

    private static RunState lockRun(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_RUN_SQL)) {
            statement.setObject(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw invalidState("Encounter run not found for reward: " + runId);
                }
                return new RunState(
                        result.getString("encounter_id"),
                        result.getInt("encounter_version"),
                        EncounterRunStatus.valueOf(result.getString("status"))
                );
            }
        }
    }

    private static List<UUID> participants(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_PARTICIPANTS_SQL)) {
            statement.setObject(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                List<UUID> participants = new ArrayList<>();
                while (result.next()) {
                    participants.add(result.getObject("player_id", UUID.class));
                }
                return List.copyOf(participants);
            }
        }
    }

    private static UUID insertReward(
            Connection connection,
            UUID rewardId,
            UUID runId,
            UUID playerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_REWARD_SQL)) {
            statement.setObject(1, rewardId);
            statement.setObject(2, runId);
            statement.setObject(3, playerId);
            statement.setString(4, REWARD_KEY);
            statement.setInt(5, REWARD_VERSION);
            statement.setString(6, CURRENCY_CODE);
            statement.setLong(7, CURRENCY_AMOUNT);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getObject("reward_id", UUID.class) : null;
            }
        }
    }

    private static RewardRow requireRewardByIdentity(
            Connection connection,
            UUID runId,
            UUID playerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_REWARD_BY_IDENTITY_SQL)) {
            statement.setObject(1, runId);
            statement.setObject(2, playerId);
            statement.setString(3, REWARD_KEY);
            statement.setInt(4, REWARD_VERSION);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw semanticConflict("Reward identity disappeared after uniqueness conflict");
                }
                return mapReward(result);
            }
        }
    }

    private static List<RewardRow> lockRewards(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_RUN_REWARDS_SQL)) {
            statement.setObject(1, runId);
            statement.setString(2, REWARD_KEY);
            statement.setInt(3, REWARD_VERSION);
            try (ResultSet result = statement.executeQuery()) {
                List<RewardRow> rewards = new ArrayList<>();
                while (result.next()) {
                    rewards.add(mapReward(result));
                }
                return List.copyOf(rewards);
            }
        }
    }

    private static void markGranted(Connection connection, UUID rewardId, UUID transactionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(MARK_GRANTED_SQL)) {
            statement.setObject(1, transactionId);
            statement.setObject(2, rewardId);
            if (statement.executeUpdate() != 1) {
                throw semanticConflict("Reward left PENDING state before grant acknowledgement");
            }
        }
    }

    private static RewardRow mapReward(ResultSet result) throws SQLException {
        return new RewardRow(
                result.getObject("reward_id", UUID.class),
                result.getObject("run_id", UUID.class),
                result.getObject("player_id", UUID.class),
                result.getString("reward_key"),
                result.getInt("reward_version"),
                result.getString("status"),
                result.getString("currency_code"),
                result.getLong("currency_amount"),
                result.getObject("currency_transaction_id", UUID.class)
        );
    }

    private static void requireRunSemantics(EncounterRun run, RunState state) {
        if (!run.encounterId().equals(state.encounterId())
                || run.encounterVersion() != state.encounterVersion()) {
            throw semanticConflict("Encounter run semantics changed before reward preparation");
        }
    }

    private static void validateRewardCoverage(
            UUID runId,
            List<UUID> participants,
            List<RewardRow> rewards
    ) {
        if (participants.isEmpty()) {
            throw semanticConflict("Completed reward run has no participants: " + runId);
        }
        if (rewards.size() != participants.size()) {
            throw invalidState("Completed run reward intent coverage is incomplete");
        }
        Set<UUID> participantSet = new HashSet<>(participants);
        Set<UUID> rewardedSet = new HashSet<>();
        for (RewardRow reward : rewards) {
            rewardedSet.add(reward.playerId());
        }
        if (!participantSet.equals(rewardedSet)) {
            throw semanticConflict("Reward intent player set does not match encounter participants");
        }
    }

    private static void validateRewardSemantics(RewardRow reward, UUID runId, UUID playerId) {
        if (!reward.runId().equals(runId)
                || !reward.playerId().equals(playerId)
                || !REWARD_KEY.equals(reward.rewardKey())
                || reward.rewardVersion() != REWARD_VERSION
                || !CURRENCY_CODE.equals(reward.currencyCode())
                || reward.currencyAmount() != CURRENCY_AMOUNT) {
            throw semanticConflict("Encounter reward identity is bound to different semantics");
        }
    }

    private static String economyIdempotencyKey(UUID rewardId) {
        return "encounter-reward:" + rewardId;
    }

    private static String prepareAuditKey(UUID rewardId) {
        return "encounter:reward:" + rewardId + ":prepared";
    }

    private static String grantAuditKey(UUID rewardId) {
        return "encounter:reward:" + rewardId + ":granted";
    }

    private static String economyMetadata(UUID runId, UUID rewardId) {
        return "{\"runId\":\"" + runId
                + "\",\"rewardId\":\"" + rewardId
                + "\",\"rewardKey\":\"" + REWARD_KEY
                + "\",\"rewardVersion\":" + REWARD_VERSION + "}";
    }

    private static String rewardPayload(
            UUID rewardId,
            UUID runId,
            UUID playerId,
            UUID transactionId
    ) {
        return "{\"rewardId\":\"" + rewardId
                + "\",\"runId\":\"" + runId
                + "\",\"playerId\":\"" + playerId
                + "\",\"rewardKey\":\"" + REWARD_KEY
                + "\",\"rewardVersion\":" + REWARD_VERSION
                + ",\"currency\":\"" + CURRENCY_CODE
                + "\",\"amount\":" + CURRENCY_AMOUNT
                + (transactionId == null ? "" : ",\"transactionId\":\"" + transactionId + "\"")
                + "}";
    }

    private static EncounterRewardException invalidState(String message) {
        return new EncounterRewardException(EncounterRewardException.Code.INVALID_STATE, message);
    }

    private static EncounterRewardException semanticConflict(String message) {
        return new EncounterRewardException(EncounterRewardException.Code.SEMANTIC_CONFLICT, message);
    }

    private static EncounterRewardException storageFailure(String message, SQLException exception) {
        if ("23505".equals(exception.getSQLState())) {
            return new EncounterRewardException(
                    EncounterRewardException.Code.SEMANTIC_CONFLICT,
                    "Encounter reward uniqueness invariant was violated",
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

    private record RunState(String encounterId, int encounterVersion, EncounterRunStatus status) {
    }

    private record RewardRow(
            UUID rewardId,
            UUID runId,
            UUID playerId,
            String rewardKey,
            int rewardVersion,
            String status,
            String currencyCode,
            long currencyAmount,
            UUID currencyTransactionId
    ) {
    }
}
