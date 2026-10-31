package dev.thedusk.minecraftserver.activity;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.encounter.EncounterDefinition;
import dev.thedusk.minecraftserver.encounter.EncounterRunStatus;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class StructuredActivityRunRepository {
    private static final String LOCK_ENCOUNTER_SQL = """
            SELECT r.encounter_id, r.encounter_version, r.definition_sha256, r.status,
                   (SELECT COUNT(*) FROM encounter_participant p WHERE p.run_id = r.run_id)
                       AS participant_count
            FROM encounter_run r
            WHERE r.run_id = ?
            FOR UPDATE
            """;

    private static final String INSERT_SQL = """
            INSERT INTO structured_activity_run (
                run_id, activity_id, activity_version, definition_sha256, current_beat
            )
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (run_id) DO NOTHING
            RETURNING *
            """;

    private static final String FIND_SQL = """
            SELECT * FROM structured_activity_run
            WHERE run_id = ?
            """;

    private static final String FIND_FOR_UPDATE_SQL = """
            SELECT a.*, r.status AS encounter_status
            FROM structured_activity_run a
            JOIN encounter_run r ON r.run_id = a.run_id
            WHERE a.run_id = ?
            FOR UPDATE OF a, r
            """;

    private static final String UPDATE_BEAT_SQL = """
            UPDATE structured_activity_run
            SET current_beat = ?, updated_at = NOW(), version = version + 1
            WHERE run_id = ? AND current_beat = ?
            RETURNING *
            """;

    private final DatabaseManager database;
    private final AuditLog auditLog;

    public StructuredActivityRunRepository(DatabaseManager database, AuditLog auditLog) {
        this.database = Objects.requireNonNull(database, "database");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    }

    public StructuredActivityRunResult attach(
            StructuredActivityDefinition activityDefinition,
            EncounterDefinition encounterDefinition,
            UUID runId,
            UUID correlationId
    ) {
        Objects.requireNonNull(activityDefinition, "activityDefinition");
        Objects.requireNonNull(encounterDefinition, "encounterDefinition");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(correlationId, "correlationId");
        validateDefinitions(activityDefinition, encounterDefinition);

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                EncounterAuthority authority = lockEncounter(connection, runId);
                validateEncounterAuthority(
                        authority,
                        activityDefinition,
                        encounterDefinition,
                        runId
                );

                StructuredActivityRun inserted = insert(
                        connection,
                        activityDefinition,
                        runId
                );
                if (inserted != null) {
                    auditLog.append(
                            connection,
                            null,
                            "activity.run_attached",
                            correlationId,
                            attachAuditKey(runId),
                            auditPayload(inserted)
                    );
                    connection.commit();
                    return new StructuredActivityRunResult(inserted, false);
                }

                StructuredActivityRun existing = requireActivity(connection, runId, false);
                if (!sameDefinition(existing, activityDefinition)) {
                    throw new StructuredActivityException(
                            StructuredActivityException.Code.IDEMPOTENCY_CONFLICT,
                            "Structured activity run is already bound to different semantics"
                    );
                }
                connection.commit();
                return new StructuredActivityRunResult(existing, true);
            } catch (StructuredActivityException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to attach structured activity run", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure(
                    "Failed to acquire structured activity database connection",
                    exception
            );
        }
    }

    public StructuredActivityRunResult advance(
            UUID runId,
            StructuredActivityBeat expectedBeat,
            StructuredActivityBeat targetBeat,
            UUID correlationId
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(expectedBeat, "expectedBeat");
        Objects.requireNonNull(targetBeat, "targetBeat");
        Objects.requireNonNull(correlationId, "correlationId");
        if (expectedBeat.next() != targetBeat) {
            throw new IllegalArgumentException(
                    "Structured activity beat transitions must advance exactly one beat"
            );
        }

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                ActivityWithEncounterStatus current = requireActivityWithStatus(connection, runId);
                if (current.encounterStatus() != EncounterRunStatus.ACTIVE) {
                    throw new StructuredActivityException(
                            StructuredActivityException.Code.INVALID_STATE,
                            "Encounter run " + runId + " is no longer active"
                    );
                }

                if (current.run().currentBeat() == targetBeat) {
                    connection.commit();
                    return new StructuredActivityRunResult(current.run(), true);
                }
                if (current.run().currentBeat() != expectedBeat) {
                    throw new StructuredActivityException(
                            StructuredActivityException.Code.INVALID_STATE,
                            "Structured activity run " + runId + " is at beat "
                                    + current.run().currentBeat() + ", expected " + expectedBeat
                    );
                }

                StructuredActivityRun updated = updateBeat(
                        connection,
                        runId,
                        expectedBeat,
                        targetBeat
                );
                auditLog.append(
                        connection,
                        null,
                        "activity.beat_started",
                        correlationId,
                        beatAuditKey(runId, targetBeat),
                        auditPayload(updated)
                );
                connection.commit();
                return new StructuredActivityRunResult(updated, false);
            } catch (StructuredActivityException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to advance structured activity beat", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure(
                    "Failed to acquire structured activity database connection",
                    exception
            );
        }
    }

    public Optional<StructuredActivityRun> find(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(FIND_SQL)) {
            statement.setObject(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to query structured activity run", exception);
        }
    }

    private static void validateDefinitions(
            StructuredActivityDefinition activityDefinition,
            EncounterDefinition encounterDefinition
    ) {
        if (!activityDefinition.encounterId().equals(encounterDefinition.id())
                || activityDefinition.encounterVersion() != encounterDefinition.version()) {
            throw new IllegalArgumentException(
                    "Structured activity definition references a different encounter definition"
            );
        }
        if (activityDefinition.minPlayers() < encounterDefinition.minPlayers()
                || activityDefinition.maxPlayers() > encounterDefinition.maxPlayers()) {
            throw new IllegalArgumentException(
                    "Structured activity party bounds must fit the encounter definition"
            );
        }
    }

    private static void validateEncounterAuthority(
            EncounterAuthority authority,
            StructuredActivityDefinition activityDefinition,
            EncounterDefinition encounterDefinition,
            UUID runId
    ) {
        if (authority.status() != EncounterRunStatus.ACTIVE) {
            throw new StructuredActivityException(
                    StructuredActivityException.Code.INVALID_STATE,
                    "Encounter run " + runId + " is no longer active"
            );
        }
        if (!authority.encounterId().equals(encounterDefinition.id())
                || authority.encounterVersion() != encounterDefinition.version()
                || !authority.definitionSha256().equals(encounterDefinition.definitionSha256())) {
            throw new StructuredActivityException(
                    StructuredActivityException.Code.IDEMPOTENCY_CONFLICT,
                    "Encounter run definition does not match the structured activity authority"
            );
        }
        if (authority.participantCount() < activityDefinition.minPlayers()
                || authority.participantCount() > activityDefinition.maxPlayers()) {
            throw new StructuredActivityException(
                    StructuredActivityException.Code.INVALID_STATE,
                    "Encounter run participant count is outside structured activity bounds"
            );
        }
    }

    private static EncounterAuthority lockEncounter(Connection connection, UUID runId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ENCOUNTER_SQL)) {
            statement.setObject(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new StructuredActivityException(
                            StructuredActivityException.Code.NOT_FOUND,
                            "Encounter run not found: " + runId
                    );
                }
                return new EncounterAuthority(
                        result.getString("encounter_id"),
                        result.getInt("encounter_version"),
                        result.getString("definition_sha256"),
                        EncounterRunStatus.valueOf(result.getString("status")),
                        result.getInt("participant_count")
                );
            }
        }
    }

    private static StructuredActivityRun insert(
            Connection connection,
            StructuredActivityDefinition definition,
            UUID runId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setObject(1, runId);
            statement.setString(2, definition.id());
            statement.setInt(3, definition.version());
            statement.setString(4, definition.definitionSha256());
            statement.setInt(5, StructuredActivityBeat.OUTER_SEAL.ordinalValue());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? map(result) : null;
            }
        }
    }

    private static StructuredActivityRun requireActivity(
            Connection connection,
            UUID runId,
            boolean forUpdate
    ) throws SQLException {
        String sql = forUpdate ? FIND_FOR_UPDATE_SQL : FIND_SQL;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new StructuredActivityException(
                            StructuredActivityException.Code.NOT_FOUND,
                            "Structured activity run not found: " + runId
                    );
                }
                return map(result);
            }
        }
    }

    private static ActivityWithEncounterStatus requireActivityWithStatus(
            Connection connection,
            UUID runId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_FOR_UPDATE_SQL)) {
            statement.setObject(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new StructuredActivityException(
                            StructuredActivityException.Code.NOT_FOUND,
                            "Structured activity run not found: " + runId
                    );
                }
                return new ActivityWithEncounterStatus(
                        map(result),
                        EncounterRunStatus.valueOf(result.getString("encounter_status"))
                );
            }
        }
    }

    private static StructuredActivityRun updateBeat(
            Connection connection,
            UUID runId,
            StructuredActivityBeat expectedBeat,
            StructuredActivityBeat targetBeat
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_BEAT_SQL)) {
            statement.setInt(1, targetBeat.ordinalValue());
            statement.setObject(2, runId);
            statement.setInt(3, expectedBeat.ordinalValue());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new StructuredActivityException(
                            StructuredActivityException.Code.INVALID_STATE,
                            "Structured activity beat changed concurrently for run " + runId
                    );
                }
                return map(result);
            }
        }
    }

    private static StructuredActivityRun map(ResultSet result) throws SQLException {
        return new StructuredActivityRun(
                result.getObject("run_id", UUID.class),
                result.getString("activity_id"),
                result.getInt("activity_version"),
                result.getString("definition_sha256"),
                StructuredActivityBeat.fromOrdinalValue(result.getInt("current_beat")),
                result.getLong("version"),
                instant(result, "created_at"),
                instant(result, "updated_at")
        );
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static boolean sameDefinition(
            StructuredActivityRun run,
            StructuredActivityDefinition definition
    ) {
        return run.activityId().equals(definition.id())
                && run.activityVersion() == definition.version()
                && run.definitionSha256().equals(definition.definitionSha256());
    }

    private static String attachAuditKey(UUID runId) {
        return "activity:run:" + runId + ":attached";
    }

    private static String beatAuditKey(UUID runId, StructuredActivityBeat beat) {
        return "activity:run:" + runId + ":beat:" + beat.ordinalValue();
    }

    private static String auditPayload(StructuredActivityRun run) {
        return "{\"runId\":\"" + run.runId()
                + "\",\"activityId\":\"" + run.activityId()
                + "\",\"activityVersion\":" + run.activityVersion()
                + ",\"definitionSha256\":\"" + run.definitionSha256()
                + "\",\"currentBeat\":\"" + run.currentBeat()
                + "\"}";
    }

    private static StructuredActivityException storageFailure(
            String message,
            SQLException exception
    ) {
        if ("23505".equals(exception.getSQLState())) {
            return new StructuredActivityException(
                    StructuredActivityException.Code.IDEMPOTENCY_CONFLICT,
                    "Structured activity uniqueness invariant was violated",
                    exception
            );
        }
        return new StructuredActivityException(
                StructuredActivityException.Code.STORAGE_FAILURE,
                message,
                exception
        );
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private record EncounterAuthority(
            String encounterId,
            int encounterVersion,
            String definitionSha256,
            EncounterRunStatus status,
            int participantCount
    ) {
    }

    private record ActivityWithEncounterStatus(
            StructuredActivityRun run,
            EncounterRunStatus encounterStatus
    ) {
    }
}
