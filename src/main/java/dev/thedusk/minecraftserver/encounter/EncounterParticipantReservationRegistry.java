package dev.thedusk.minecraftserver.encounter;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Process-local reservation authority preventing one Minecraft player from being admitted to
 * overlapping encounter runtimes while durable start/activation work is in flight.
 *
 * <p>This is deliberately not durable encounter state. PostgreSQL remains the source of truth for
 * encounter runs and canonical participants; this registry only closes the in-process race between
 * the standalone boss surface and structured-activity runtime.</p>
 */
public final class EncounterParticipantReservationRegistry {
    private final Set<UUID> reserved = new HashSet<>();

    /**
     * Atomically reserves every supplied player or returns the first conflicting UUID.
     */
    public synchronized Optional<UUID> tryReserve(Collection<UUID> participantUuids) {
        LinkedHashSet<UUID> normalized = normalize(participantUuids);
        for (UUID participantUuid : normalized) {
            if (reserved.contains(participantUuid)) {
                return Optional.of(participantUuid);
            }
        }
        reserved.addAll(normalized);
        return Optional.empty();
    }

    public synchronized void release(Collection<UUID> participantUuids) {
        Objects.requireNonNull(participantUuids, "participantUuids");
        for (UUID participantUuid : participantUuids) {
            reserved.remove(Objects.requireNonNull(participantUuid, "participantUuid"));
        }
    }

    public synchronized boolean isReserved(UUID participantUuid) {
        return reserved.contains(Objects.requireNonNull(participantUuid, "participantUuid"));
    }

    public synchronized boolean areAllReserved(Collection<UUID> participantUuids) {
        LinkedHashSet<UUID> normalized = normalize(participantUuids);
        return reserved.containsAll(normalized);
    }

    public synchronized int size() {
        return reserved.size();
    }

    private static LinkedHashSet<UUID> normalize(Collection<UUID> participantUuids) {
        Objects.requireNonNull(participantUuids, "participantUuids");
        if (participantUuids.isEmpty()) {
            throw new IllegalArgumentException("participantUuids must not be empty");
        }
        LinkedHashSet<UUID> normalized = new LinkedHashSet<>();
        for (UUID participantUuid : participantUuids) {
            normalized.add(Objects.requireNonNull(participantUuid, "participantUuid"));
        }
        return normalized;
    }
}
