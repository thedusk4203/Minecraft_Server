package dev.thedusk.minecraftserver.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.identity.PlayerIdentity;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.DatabaseSettings;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@Testcontainers
class MarketListingRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_market_test")
            .withUsername("minecraft_test")
            .withPassword("minecraft_test_password");

    private static DatabaseManager database;
    private static PlayerIdentityRepository identities;
    private static MarketListingRepository listings;

    @BeforeAll
    static void openDatabase() {
        openServices();
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void clearState() throws Exception {
        try (var connection = database.connection();
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE market_listing, economy_transaction, economy_account, audit_event, player_onboarding, player_identity CASCADE");
        }
    }

    @Test
    void preparationIsIdempotentByEscrowToken() throws Exception {
        PlayerIdentity seller = seller("PrepareSeller");
        UUID token = UUID.randomUUID();
        UUID originalCorrelation = UUID.randomUUID();
        MarketListingDraft draft = draft(seller.playerId(), token, 125L, originalCorrelation);

        MarketListing first = listings.prepare(draft);
        MarketListing replay = listings.prepare(draft(
                seller.playerId(),
                token,
                125L,
                UUID.randomUUID()
        ));

        assertEquals(first.listingId(), replay.listingId());
        assertEquals(first.listingNumber(), replay.listingNumber());
        assertEquals(originalCorrelation, replay.prepareCorrelationId());
        assertEquals(MarketListingStatus.PREPARED, replay.status());
        assertEquals(1L, countRows("market_listing"));
        assertEquals(1L, countAudit("market.listing_prepared"));
    }

    @Test
    void sameEscrowTokenWithDifferentSemanticsFailsClosed() {
        PlayerIdentity seller = seller("ConflictSeller");
        UUID token = UUID.randomUUID();
        listings.prepare(draft(seller.playerId(), token, 100L, UUID.randomUUID()));

        MarketException exception = assertThrows(
                MarketException.class,
                () -> listings.prepare(draft(seller.playerId(), token, 101L, UUID.randomUUID()))
        );

        assertEquals(MarketException.Code.IDEMPOTENCY_CONFLICT, exception.code());
        MarketListing durable = listings.findByEscrowToken(token).orElseThrow();
        assertEquals(100L, durable.priceAmount());
        assertEquals(MarketListingStatus.PREPARED, durable.status());
    }

    @Test
    void lifecycleTransitionsAreIdempotentAndAudited() throws Exception {
        PlayerIdentity seller = seller("LifecycleSeller");
        MarketListing prepared = listings.prepare(draft(
                seller.playerId(), UUID.randomUUID(), 75L, UUID.randomUUID()
        ));

        MarketListing listed = listings.activatePrepared(prepared.listingId(), seller.playerId());
        MarketListing listedReplay = listings.activatePrepared(prepared.listingId(), seller.playerId());
        assertEquals(MarketListingStatus.LISTED, listed.status());
        assertEquals(listed.listedAt(), listedReplay.listedAt());
        assertNotNull(listed.listedAt());

        UUID returnCorrelation = UUID.randomUUID();
        MarketListing pending = listings.requestReturn(
                listed.listingNumber(), seller.playerId(), returnCorrelation
        );
        MarketListing pendingReplay = listings.requestReturn(
                listed.listingNumber(), seller.playerId(), UUID.randomUUID()
        );
        assertEquals(MarketListingStatus.RETURN_PENDING, pending.status());
        assertEquals(returnCorrelation, pending.returnCorrelationId());
        assertEquals(returnCorrelation, pendingReplay.returnCorrelationId());
        assertNotNull(pending.returnRequestedAt());

        MarketListing cancelled = listings.completeCancel(pending.listingId(), seller.playerId());
        MarketListing cancelledReplay = listings.completeCancel(pending.listingId(), seller.playerId());
        assertEquals(MarketListingStatus.CANCELLED, cancelled.status());
        assertEquals(cancelled.cancelledAt(), cancelledReplay.cancelledAt());
        assertNotNull(cancelled.cancelledAt());

        assertEquals(1L, countAudit("market.listing_prepared"));
        assertEquals(1L, countAudit("market.listing_listed"));
        assertEquals(1L, countAudit("market.listing_return_requested"));
        assertEquals(1L, countAudit("market.listing_cancelled"));
        assertEquals(4L, countRows("audit_event"));
    }

    @Test
    void wrongSellerCannotCancelAnotherPlayersListing() {
        PlayerIdentity seller = seller("OwnerSeller");
        PlayerIdentity attacker = seller("OtherSeller");
        MarketListing listed = listings.activatePrepared(
                listings.prepare(draft(seller.playerId(), UUID.randomUUID(), 50L, UUID.randomUUID())).listingId(),
                seller.playerId()
        );

        MarketException exception = assertThrows(
                MarketException.class,
                () -> listings.requestReturn(listed.listingNumber(), attacker.playerId(), UUID.randomUUID())
        );

        assertEquals(MarketException.Code.NOT_OWNER, exception.code());
        MarketListing durable = listings.findByNumber(listed.listingNumber()).orElseThrow();
        assertEquals(MarketListingStatus.LISTED, durable.status());
        assertNull(durable.returnRequestedAt());
        assertNull(durable.returnCorrelationId());
    }

    @Test
    void activeAndSellerQueriesExposeOnlyOpenState() {
        PlayerIdentity seller = seller("QuerySeller");
        MarketListing first = listings.prepare(draft(seller.playerId(), UUID.randomUUID(), 10L, UUID.randomUUID()));
        MarketListing second = listings.prepare(draft(seller.playerId(), UUID.randomUUID(), 20L, UUID.randomUUID()));
        MarketListing third = listings.prepare(draft(seller.playerId(), UUID.randomUUID(), 30L, UUID.randomUUID()));

        MarketListing listedFirst = listings.activatePrepared(first.listingId(), seller.playerId());
        MarketListing listedSecond = listings.activatePrepared(second.listingId(), seller.playerId());
        MarketListing pending = listings.requestReturn(
                listedSecond.listingNumber(), seller.playerId(), UUID.randomUUID()
        );
        listings.completeCancel(pending.listingId(), seller.playerId());

        assertEquals(1, listings.listActive(20).size());
        assertEquals(listedFirst.listingId(), listings.listActive(20).getFirst().listingId());
        assertEquals(2, listings.findOpenBySeller(seller.playerId()).size());
        assertTrue(listings.findOpenBySeller(seller.playerId()).stream()
                .anyMatch(listing -> listing.listingId().equals(third.listingId())));
    }

    @Test
    void restartPreservesPreparedAndListedSnapshots() throws Exception {
        PlayerIdentity seller = seller("RestartSeller");
        MarketListing prepared = listings.prepare(draft(
                seller.playerId(), UUID.randomUUID(), 99L, UUID.randomUUID()
        ));
        MarketListing listed = listings.activatePrepared(prepared.listingId(), seller.playerId());
        byte[] expectedPayload = listed.itemPayload();

        database.close();
        openServices();

        MarketListing afterRestart = listings.findByNumber(listed.listingNumber()).orElseThrow();
        assertEquals(MarketListingStatus.LISTED, afterRestart.status());
        assertEquals(listed.listingId(), afterRestart.listingId());
        assertEquals(listed.itemSha256(), afterRestart.itemSha256());
        assertEquals(expectedPayload.length, afterRestart.itemPayload().length);
        for (int i = 0; i < expectedPayload.length; i++) {
            assertEquals(expectedPayload[i], afterRestart.itemPayload()[i]);
        }
    }

    private static void openServices() {
        database = DatabaseManager.open(new DatabaseSettings(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                4
        ));
        identities = new PlayerIdentityRepository(database);
        listings = new MarketListingRepository(database, new AuditLog());
    }

    private static PlayerIdentity seller(String name) {
        return identities.recordSeen(UUID.randomUUID(), name);
    }

    private static MarketListingDraft draft(
            UUID sellerPlayerId,
            UUID token,
            long price,
            UUID correlationId
    ) {
        byte[] payload = {10, 20, 30, 40};
        return new MarketListingDraft(
                sellerPlayerId,
                token,
                payload,
                sha256(payload),
                "minecraft:diamond",
                3,
                "COINS",
                price,
                correlationId
        );
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static long countRows(String table) throws Exception {
        try (var connection = database.connection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long countAudit(String eventType) throws Exception {
        try (var connection = database.connection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM audit_event WHERE event_type = ?")) {
            statement.setString(1, eventType);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
