package dev.thedusk.minecraftserver.boss;

import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.PersistenceException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.UUID;

public final class BossTelemetryRepository {
    private static final String INSERT_AUDIT_SQL = """
            INSERT INTO audit_event (
                event_id, actor_player_id, event_type, correlation_id,
                idempotency_key, payload, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB), NOW())
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

    private final DatabaseManager database;

    public BossTelemetryRepository(DatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public void participantEliminated(
            UUID runId,
            UUID internalPlayerId,
            String cause,
            UUID correlationId
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(internalPlayerId, "internalPlayerId");
        Objects.requireNonNull(correlationId, "correlationId");
        String normalizedCause = normalizeToken(cause, "cause");
        append(
                internalPlayerId,
                "boss.participant_eliminated",
                correlationId,
                "boss:run:" + runId + ":eliminated:" + internalPlayerId,
                "{\"runId\":\"" + runId + "\",\"cause\":\"" + normalizedCause + "\"}"
        );
    }

    public void attemptFinished(
            UUID runId,
            String outcome,
            String reason,
            long durationMillis,
            UUID correlationId
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must be non-negative");
        }
        String normalizedOutcome = normalizeToken(outcome, "outcome");
        String normalizedReason = reason == null ? "NONE" : normalizeToken(reason, "reason");
        append(
                null,
                "boss.attempt_finished",
                correlationId,
                "boss:run:" + runId + ":finished",
                "{\"runId\":\"" + runId
                        + "\",\"outcome\":\"" + normalizedOutcome
                        + "\",\"reason\":\"" + normalizedReason
                        + "\",\"durationMillis\":" + durationMillis + "}"
        );
    }

    private void append(
            UUID actorPlayerId,
            String eventType,
            UUID correlationId,
            String idempotencyKey,
            String payload
    ) {
        try (var connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(INSERT_AUDIT_SQL)) {
            statement.setObject(1, UUID.randomUUID());
            if (actorPlayerId == null) {
                statement.setNull(2, Types.OTHER);
            } else {
                statement.setObject(2, actorPlayerId);
            }
            statement.setString(3, eventType);
            statement.setObject(4, correlationId);
            statement.setString(5, idempotencyKey);
            statement.setString(6, payload);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new PersistenceException("Failed to persist boss telemetry event " + eventType, exception);
        }
    }

    private static String normalizeToken(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[A-Z0-9_]{1,48}")) {
            throw new IllegalArgumentException(field + " must match [A-Z0-9_]{1,48}");
        }
        return normalized;
    }
}
