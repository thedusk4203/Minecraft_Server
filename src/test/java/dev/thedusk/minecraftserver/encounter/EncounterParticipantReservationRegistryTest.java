package dev.thedusk.minecraftserver.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EncounterParticipantReservationRegistryTest {
    @Test
    void reserveIsAtomicAcrossWholeParty() {
        EncounterParticipantReservationRegistry registry = new EncounterParticipantReservationRegistry();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        assertTrue(registry.tryReserve(List.of(first, second)).isEmpty());
        assertEquals(first, registry.tryReserve(List.of(third, first)).orElseThrow());
        assertFalse(registry.isReserved(third));
        assertEquals(2, registry.size());
    }

    @Test
    void releaseAllowsLaterReservation() {
        EncounterParticipantReservationRegistry registry = new EncounterParticipantReservationRegistry();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(registry.tryReserve(List.of(first, second)).isEmpty());
        assertTrue(registry.areAllReserved(List.of(first, second)));

        registry.release(List.of(first, second));

        assertFalse(registry.isReserved(first));
        assertFalse(registry.isReserved(second));
        assertTrue(registry.tryReserve(List.of(first)).isEmpty());
    }

    @Test
    void duplicateInputDoesNotCreateDuplicateReservations() {
        EncounterParticipantReservationRegistry registry = new EncounterParticipantReservationRegistry();
        UUID player = UUID.randomUUID();

        assertTrue(registry.tryReserve(List.of(player, player)).isEmpty());
        assertEquals(1, registry.size());
        assertTrue(registry.areAllReserved(List.of(player, player)));
    }
}
