package dev.thedusk.minecraftserver.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
                sha256(payload),
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
    void rejectsInvalidHashMaterialAndPrice() {
        byte[] payload = {1};
        assertThrows(
                IllegalArgumentException.class,
                () -> draft(payload, "not-a-hash", "minecraft:stone", 10L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> draft(payload, "a".repeat(64), "minecraft:stone", 10L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> draft(payload, sha256(payload), "not a material", 10L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> draft(payload, sha256(payload), "minecraft:stone", 0L)
        );
    }

    @Test
    void rejectsEmptyOrOversizedPayload() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketListingDraft(
                        UUID.randomUUID(), UUID.randomUUID(), new byte[0], sha256(new byte[0]),
                        "minecraft:stone", 1, "COINS", 1L, UUID.randomUUID()
                )
        );
        byte[] oversized = new byte[1_048_577];
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarketListingDraft(
                        UUID.randomUUID(), UUID.randomUUID(), oversized, sha256(oversized),
                        "minecraft:stone", 1, "COINS", 1L, UUID.randomUUID()
                )
        );
    }

    private static MarketListingDraft draft(byte[] payload, String hash, String material, long price) {
        return new MarketListingDraft(
                UUID.randomUUID(),
                UUID.randomUUID(),
                payload,
                hash,
                material,
                1,
                "COINS",
                price,
                UUID.randomUUID()
        );
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
