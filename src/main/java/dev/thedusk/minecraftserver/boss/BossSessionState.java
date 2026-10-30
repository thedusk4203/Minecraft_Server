package dev.thedusk.minecraftserver.boss;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class BossSessionState {
    private final UUID runId;
    private final UUID bossEntityId;
    private final Instant startedAt;
    private final Map<UUID, UUID> internalPlayerIdByMinecraftUuid;
    private final Set<UUID> eliminatedMinecraftUuids = new LinkedHashSet<>();
    private boolean terminalRequested;

    public BossSessionState(
            UUID runId,
            UUID bossEntityId,
            Instant startedAt,
            Map<UUID, UUID> internalPlayerIdByMinecraftUuid
    ) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.bossEntityId = Objects.requireNonNull(bossEntityId, "bossEntityId");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(internalPlayerIdByMinecraftUuid, "internalPlayerIdByMinecraftUuid");
        if (internalPlayerIdByMinecraftUuid.isEmpty()) {
            throw new IllegalArgumentException("boss session must contain at least one participant");
        }
        LinkedHashMap<UUID, UUID> copy = new LinkedHashMap<>();
        internalPlayerIdByMinecraftUuid.forEach((minecraftUuid, playerId) -> copy.put(
                Objects.requireNonNull(minecraftUuid, "minecraftUuid"),
                Objects.requireNonNull(playerId, "playerId")
        ));
        this.internalPlayerIdByMinecraftUuid = Map.copyOf(copy);
    }

    public UUID runId() {
        return runId;
    }

    public UUID bossEntityId() {
        return bossEntityId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Set<UUID> participantMinecraftUuids() {
        return internalPlayerIdByMinecraftUuid.keySet();
    }

    public UUID internalPlayerId(UUID minecraftUuid) {
        return internalPlayerIdByMinecraftUuid.get(minecraftUuid);
    }

    public boolean isParticipant(UUID minecraftUuid) {
        return internalPlayerIdByMinecraftUuid.containsKey(minecraftUuid);
    }

    public boolean isEliminated(UUID minecraftUuid) {
        return eliminatedMinecraftUuids.contains(minecraftUuid);
    }

    public boolean eliminate(UUID minecraftUuid) {
        if (!isParticipant(minecraftUuid)) {
            return false;
        }
        return eliminatedMinecraftUuids.add(minecraftUuid);
    }

    public int remainingParticipants() {
        return internalPlayerIdByMinecraftUuid.size() - eliminatedMinecraftUuids.size();
    }

    public boolean beginTerminal() {
        if (terminalRequested) {
            return false;
        }
        terminalRequested = true;
        return true;
    }

    public boolean terminalRequested() {
        return terminalRequested;
    }
}
