package dev.thedusk.minecraftserver.encounter;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class EncounterRunRepository {
    private static final String INSERT_RUN_SQL = """
            INSERT INTO encounter_run (
                run_id, encounter_id, encounter_version, definition_sha256,
                status, start_correlation_id
            )
            VALUES (?, ?, ?, ?, 'ACTIVE', ?)
            ON CONFLICT (start_correlation_id) DO NOTHING
            RETURNING *
            """;

    private static final String INSERT_PARTICIPANT_SQL = """
            INSERT INTO encounter_participant (run_id, player_id)
            VALUES (?, ?)
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT * FROM encounter_run
            WHERE run_id = ?
            """;

    private static final String FIND_BY_ID_FOR_UPDATE_SQL = FIND_BY_ID_SQL + " FOR UPDATE";

    private static final String FIND_BY_START_CORRELATION_FOR_UPDATE_SQL = """
            SELECT * FROM encounter_run
            WHERE start_correlation_id = ?
            FOR UPDATE
            """;

    private static final String FIND_PARTICIPANTS_SQL = """
            SELECT player_id
            FROM encounter_participant
            WHERE run_id = ?
            ORDER BY player_id
            """;

    private static final String TERMINATE_SQL = """
            UPDATE encounter_run
            SET status = ?, ended_at = NOW(), terminal_correlation_id = ?, terminal_reason = ?,
                updated_at = NOW(), version = version + 1
            WHERE run_id = ? AND status = 'ACTIVE'
            RETURNING *
            """;

    private static final String LOCK_ACTIVE_SQL = """
            SELECT * FROM encounter_run
            WHERE status = 'ACTIVE'
            ORDER BY started_at, run_id
            FOR UPDATE
            """;

    private final DatabaseManager database;
    private final AuditLog auditLog;

    public EncounterRunRepository(DatabaseManager database, AuditLog auditLog) {
        this.database = Objects.requireNonNull(database, "database");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    }

    public EncounterRunResult start(
            EncounterDefinition definition,
            List<UUID> participantPlayerIds,
            UUID correlationId
    ) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(correlationId, "correlationId");
        List<UUID> participants = normalizeParticipants(definition, participantPlayerIds);

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                EncounterRun inserted = insertRun(connection, definition, correlationId);
                if (inserted != null) {
                    insertParticipants(connection, inserted.runId(), participants);
                    EncounterRun hydrated = hydrate(connection, inserted);
                    auditLog.append(
                            connection,
                            null,
                            "encounter.run_started",
                            correlationId,
                            startAuditKey(correlationId),
                            auditPayload(hydrated)
                    );
                    connection.commit();
                    return new EncounterRunResult(hydrated, false);
                }

                EncounterRun existing = requireByStartCorrelation(connection, correlationId);
                EncounterRun hydrated = hydrate(connection, existing);
                if (!sameStart(hydrated, definition, participants)) {
                    throw new EncounterException(
                            EncounterException.Code.IDEMPOTENCY_CONFLICT,
                            "Encounter start correlation is already bound to different semantics"
                    );
                }
                connection.commit();
                return new EncounterRunResult(hydrated, true);
            } catch (EncounterException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to start encounter run", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire encounter database connection", exception);
        }
    }

    public EncounterRunResult complete(UUID runId, UUID correlationId) {
        return terminate(runId, EncounterRunStatus.COMPLETED, correlationId, null);
    }

    public EncounterRunResult fail(UUID runId, UUID correlationId, String reason) {
        return terminate(runId, EncounterRunStatus.FAILED, correlationId, requireReason(reason));
    }

    public EncounterRunResult abandon(UUID runId, UUID correlationId, String reason) {
        return terminate(runId, EncounterRunStatus.ABANDONED, correlationId, requireReason(reason));
    }

    public int recoverInterruptedRuns(UUID correlationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                List<EncounterRun> active = lockActive(connection);
                for (EncounterRun run : active) {
                    EncounterRun abandoned = updateTerminal(
                            connection,
                            run.runId(),
                            EncounterRunStatus.ABANDONED,
                            correlationId,
                            "SERVER_RESTART"
                    );
                    EncounterRun hydrated = hydrate(connection, abandoned);
                    auditLog.append(
                            connection,
                            null,
                            "encounter.run_abandoned",
                            correlationId,
                            terminalAuditKey(run.runId()),
                            auditPayload(hydrated)
                    );
                }
                connection.commit();
                return active.size();
            } catch (EncounterException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to recover interrupted encounter runs", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire encounter recovery connection", exception);
        }
    }

    public Optional<EncounterRun> find(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(FIND_BY_ID_SQL)) {
            statement.setObject(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(hydrate(connection, map(result)));
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to query encounter run", exception);
        }
    }

    private EncounterRunResult terminate(
            UUID runId,
            EncounterRunStatus target,
            UUID correlationId,
            String reason
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(correlationId, "correlationId");
        if (!target.terminal()) {
            throw new IllegalArgumentException("target status must be terminal");
        }

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                EncounterRun current = requireById(connection, runId, true);
                if (current.status() == target) {
                    EncounterRun hydrated = hydrate(connection, current);
                    connection.commit();
                    return new EncounterRunResult(hydrated, true);
                }
                if (current.status().terminal()) {
                    throw new EncounterException(
                            EncounterException.Code.INVALID_STATE,
                            "Encounter run " + runId + " already terminated as " + current.status()
                    );
                }

                EncounterRun updated = updateTerminal(
                        connection,
                        runId,
                        target,
                        correlationId,
                        reason
                );
                EncounterRun hydrated = hydrate(connection, updated);
                auditLog.append(
                        connection,
                        null,
                        terminalEventType(target),
                        correlationId,
                        terminalAuditKey(runId),
                        auditPayload(hydrated)
                );
                connection.commit();
                return new EncounterRunResult(hydrated, false);
            } catch (EncounterException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to terminate encounter run", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire encounter database connection", exception);
        }
    }

    private EncounterRun insertRun(
            Connection connection,
            EncounterDefinition definition,
            UUID correlationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_RUN_SQL)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, definition.id());
            statement.setInt(3, definition.version());
            statement.setString(4, definition.definitionSha256());
            statement.setObject(5, correlationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? map(result) : null;
            }
        }
    }

    private static void insertParticipants(
            Connection connection,
            UUID runId,
            List<UUID> participants
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PARTICIPANT_SQL)) {
            for (UUID participant : participants) {
                statement.setObject(1, runId);
                statement.setObject(2, participant);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private EncounterRun requireByStartCorrelation(Connection connection, UUID correlationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                FIND_BY_START_CORRELATION_FOR_UPDATE_SQL
        )) {
            statement.setObject(1, correlationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new EncounterException(
                            EncounterException.Code.NOT_FOUND,
                            "Encounter run missing for start correlation " + correlationId
                    );
                }
                return map(result);
            }
        }
    }

    private EncounterRun requireById(Connection connection, UUID runId, boolean forUpdate)
            throws SQLException {
        String sql = forUpdate ? FIND_BY_ID_FOR_UPDATE_SQL : FIND_BY_ID_SQL;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new EncounterException(
                            EncounterException.Code.NOT_FOUND,
                            "Encounter run not found: " + runId
                    );
                }
                return map(result);
            }
        }
    }

    private List<EncounterRun> lockActive(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_ACTIVE_SQL);
             ResultSet result = statement.executeQuery()) {
            List<EncounterRun> runs = new ArrayList<>();
            while (result.next()) {
                runs.add(map(result));
            }
            return List.copyOf(runs);
        }
    }

    private EncounterRun updateTerminal(
            Connection connection,
            UUID runId,
            EncounterRunStatus status,
            UUID correlationId,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TERMINATE_SQL)) {
            statement.setString(1, status.name());
            statement.setObject(2, correlationId);
            statement.setString(3, reason);
            statement.setObject(4, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new EncounterException(
                            EncounterException.Code.INVALID_STATE,
                            "Encounter run is no longer active: " + runId
                    );
                }
                return map(result);
            }
        }
    }

    private EncounterRun hydrate(Connection connection, EncounterRun run) throws SQLException {
        return new EncounterRun(
                run.runId(),
                run.encounterId(),
                run.encounterVersion(),
                run.definitionSha256(),
                run.status(),
                run.startCorrelationId(),
                run.startedAt(),
                run.endedAt(),
                run.terminalCorrelationId(),
                run.terminalReason(),
                run.version(),
                participants(connection, run.runId())
        );
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

    private static EncounterRun map(ResultSet result) throws SQLException {
        return new EncounterRun(
                result.getObject("run_id", UUID.class),
                result.getString("encounter_id"),
                result.getInt("encounter_version"),
                result.getString("definition_sha256"),
                EncounterRunStatus.valueOf(result.getString("status")),
                result.getObject("start_correlation_id", UUID.class),
                instant(result, "started_at"),
                instant(result, "ended_at"),
                result.getObject("terminal_correlation_id", UUID.class),
                result.getString("terminal_reason"),
                result.getLong("version"),
                List.of()
        );
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static List<UUID> normalizeParticipants(
            EncounterDefinition definition,
            List<UUID> participantPlayerIds
    ) {
        Objects.requireNonNull(participantPlayerIds, "participantPlayerIds");
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (UUID playerId : participantPlayerIds) {
            unique.add(Objects.requireNonNull(playerId, "participantPlayerId"));
        }
        List<UUID> normalized = unique.stream().sorted().toList();
        if (normalized.size() != participantPlayerIds.size()) {
            throw new IllegalArgumentException("Encounter participants must be unique");
        }
        if (normalized.size() < definition.minPlayers()
                || normalized.size() > definition.maxPlayers()) {
            throw new IllegalArgumentException(
                    "Encounter '" + definition.id() + "' requires "
                            + definition.minPlayers() + "-" + definition.maxPlayers() + " players"
            );
        }
        return normalized;
    }

    private static boolean sameStart(
            EncounterRun run,
            EncounterDefinition definition,
            List<UUID> participants
    ) {
        return run.encounterId().equals(definition.id())
                && run.encounterVersion() == definition.version()
                && run.definitionSha256().equals(definition.definitionSha256())
                && run.participantPlayerIds().equals(participants);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("terminal reason must not be blank");
        }
        String normalized = reason.trim();
        if (normalized.length() > 160) {
            throw new IllegalArgumentException("terminal reason must be at most 160 characters");
        }
        return normalized;
    }

    private static String terminalEventType(EncounterRunStatus status) {
        return switch (status) {
            case COMPLETED -> "encounter.run_completed";
            case FAILED -> "encounter.run_failed";
            case ABANDONED -> "encounter.run_abandoned";
            case ACTIVE -> throw new IllegalArgumentException("ACTIVE is not terminal");
        };
    }

    private static String startAuditKey(UUID correlationId) {
        return "encounter:start:" + correlationId;
    }

    private static String terminalAuditKey(UUID runId) {
        return "encounter:run:" + runId + ":terminal";
    }

    private static String auditPayload(EncounterRun run) {
        return "{\"runId\":\"" + run.runId()
                + "\",\"encounterId\":\"" + run.encounterId()
                + "\",\"encounterVersion\":" + run.encounterVersion()
                + ",\"definitionSha256\":\"" + run.definitionSha256()
                + "\",\"status\":\"" + run.status()
                + "\",\"participantCount\":" + run.participantPlayerIds().size()
                + "}";
    }

    private static EncounterException storageFailure(String message, SQLException exception) {
        if ("23505".equals(exception.getSQLState())) {
            return new EncounterException(
                    EncounterException.Code.IDEMPOTENCY_CONFLICT,
                    "Encounter uniqueness invariant was violated",
                    exception
            );
        }
        return new EncounterException(EncounterException.Code.STORAGE_FAILURE, message, exception);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }
}
