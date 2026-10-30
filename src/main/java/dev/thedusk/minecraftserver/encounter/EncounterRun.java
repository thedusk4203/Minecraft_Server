package dev.thedusk.minecraftserver.encounter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EncounterRun(
        UUID runId,
        String encounterId,
        int encounterVersion,
        String definitionSha256,
        EncounterRunStatus status,
        UUID startCorrelationId,
        Instant startedAt,
        Instant endedAt,
        UUID terminalCorrelationId,
        String terminalReason,
        long version,
        List<UUID> participantPlayerIds
) {
    public EncounterRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(definitionSha256, "definitionSha256");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startCorrelationId, "startCorrelationId");
        Objects.requireNonNull(startedAt, "startedAt");
        participantPlayerIds = List.copyOf(participantPlayerIds);
    }
}
