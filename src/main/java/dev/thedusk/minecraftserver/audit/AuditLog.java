package dev.thedusk.minecraftserver.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.UUID;

public final class AuditLog {
    private static final String INSERT_SQL = """
            INSERT INTO audit_event (
                event_id, actor_player_id, event_type, correlation_id,
                idempotency_key, payload, occurred_at
            )
            VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB), NOW())
            """;

    public void append(
            Connection connection,
            UUID actorPlayerId,
            String eventType,
            UUID correlationId,
            String idempotencyKey,
            String payloadJson
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(correlationId, "correlationId");
        String normalizedType = requireLength(eventType, "eventType", 96);
        String normalizedIdempotency = optionalLength(idempotencyKey, "idempotencyKey", 160);
        String normalizedPayload = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;

        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setObject(1, UUID.randomUUID());
            if (actorPlayerId == null) {
                statement.setNull(2, Types.OTHER);
            } else {
                statement.setObject(2, actorPlayerId);
            }
            statement.setString(3, normalizedType);
            statement.setObject(4, correlationId);
            statement.setString(5, normalizedIdempotency);
            statement.setString(6, normalizedPayload);
            statement.executeUpdate();
        }
    }

    private static String requireLength(String value, String field, int maximum) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " must be at most " + maximum + " characters");
        }
        return normalized;
    }

    private static String optionalLength(String value, String field, int maximum) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireLength(value, field, maximum);
    }
}
