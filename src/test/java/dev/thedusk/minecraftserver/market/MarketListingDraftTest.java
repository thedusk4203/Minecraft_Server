package dev.thedusk.minecraftserver.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketListingDraftTest {
    @Test
    void normalizesCurrencyMaterialAndCopiesPayload() {
        byte[] payload = {1, 2, 3};
        MarketListingDraft draft = new MarketListingDraft(
                UUID.randomUUID(),
                UUID.randomUUID(),
                payload,
                "a".repeat(64),
                " Minecraft:Diamond ",
                4,
                " coins ",
                25L,
                UUID.randomUUID()
        );

        payload[0] = 99;
        assertEquals(1, draft.itemPayload()[0]);
        assertEquals("minecraft:diamond", draft.materialKey());
        assertEquals("COINS", draft.currencyCode());
    }

    @Test
    void rejectsInvalidHashAndPrice() {
        assertThrows(
                IllegalArgumentException.class,
                () -> draft("not-a-hash", 10L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> draft("a".repeat(64), 0L)
        );
    }

    @Test
    void rejectsEmptyOrOversizedPayload() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketListingDraft(
                        UUID.randomUUID(), UUID.randomUUID(), new byte[0], "a".repeat(64),
                        "minecraft:stone", 1, "COINS", 1L, UUID.randomUUID()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketListingDraft(
                        UUID.randomUUID(), UUID.randomUUID(), new byte[1_048_577], "a".repeat(64),
                        "minecraft:stone", 1, "COINS", 1L, UUID.randomUUID()
                )
        );
    }

    private static MarketListingDraft draft(String hash, long price) {
        return new MarketListingDraft(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new byte[]{1},
                hash,
                "minecraft:stone",
                1,
                "COINS",
                price,
                UUID.randomUUID()
        );
    }
}
