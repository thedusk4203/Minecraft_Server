package dev.thedusk.minecraftserver.activity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StructuredActivityRun(
        UUID runId,
        String activityId,
        int activityVersion,
        String definitionSha256,
        StructuredActivityBeat currentBeat,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public StructuredActivityRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(activityId, "activityId");
        Objects.requireNonNull(definitionSha256, "definitionSha256");
        Objects.requireNonNull(currentBeat, "currentBeat");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
