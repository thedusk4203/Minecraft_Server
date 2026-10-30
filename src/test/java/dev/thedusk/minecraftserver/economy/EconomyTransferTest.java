package dev.thedusk.minecraftserver.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EconomyTransferTest {
    @Test
    void normalizesCurrencyAndDerivesLedgerKeys() {
        EconomyTransfer transfer = new EconomyTransfer(
                UUID.randomUUID(),
                UUID.randomUUID(),
                " coins ",
                25L,
                " market.trade ",
                "trade:123",
                UUID.randomUUID(),
                null
        );

        assertEquals("COINS", transfer.currencyCode());
        assertEquals("market.trade", transfer.reason());
        assertEquals("{}", transfer.metadataJson());
        assertEquals("trade:123:debit", transfer.debitIdempotencyKey());
        assertEquals("trade:123:credit", transfer.creditIdempotencyKey());
    }

    @Test
    void rejectsSelfTransfer() {
        UUID playerId = UUID.randomUUID();
        assertThrows(
                IllegalArgumentException.class,
                () -> new EconomyTransfer(
                        playerId,
                        playerId,
                        "COINS",
                        1L,
                        "market.trade",
                        "trade:self",
                        UUID.randomUUID(),
                        "{}"
                )
        );
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EconomyTransfer(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "COINS",
                        0L,
                        "market.trade",
                        "trade:zero",
                        UUID.randomUUID(),
                        "{}"
                )
        );
    }

    @Test
    void reservesSuffixSpaceInIdempotencyKey() {
        String tooLong = "x".repeat(121);
        assertThrows(
                IllegalArgumentException.class,
                () -> new EconomyTransfer(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "COINS",
                        1L,
                        "market.trade",
                        tooLong,
                        UUID.randomUUID(),
                        "{}"
                )
        );
    }
}
