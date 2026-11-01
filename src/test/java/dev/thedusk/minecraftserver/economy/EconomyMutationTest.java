package dev.thedusk.minecraftserver.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EconomyMutationTest {

    @Test
    void normalizesCurrencyAndMetadata() {
        EconomyMutation mutation = new EconomyMutation(
                UUID.randomUUID(),
                " coins ",
                25L,
                "quest.reward",
                "quest:123",
                UUID.randomUUID(),
                null
        );

        assertEquals("COINS", mutation.currencyCode());
        assertEquals("{}", mutation.metadataJson());
    }

    @Test
    void rejectsZeroDelta() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EconomyMutation(
                        UUID.randomUUID(),
                        "COINS",
                        0L,
                        "test",
                        "test:zero",
                        UUID.randomUUID(),
                        "{}"
                )
        );
    }

    @Test
    void rejectsInvalidCurrencyCode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EconomyMutation.normalizeCurrencyCode("coins-with-dash")
        );
    }
}
