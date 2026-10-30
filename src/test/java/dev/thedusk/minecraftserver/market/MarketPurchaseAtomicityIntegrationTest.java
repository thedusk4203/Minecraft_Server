package dev.thedusk.minecraftserver.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.economy.EconomyMutation;
import dev.thedusk.minecraftserver.identity.PlayerIdentity;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.onboarding.OnboardingProgressRepository;
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
class MarketPurchaseAtomicityIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_purchase_atomicity_test")
            .withUsername("minecraft_test")
            .withPassword("minecraft_test_password");

    private static DatabaseManager database;
    private static PlayerIdentityRepository identities;
    private static OnboardingProgressRepository onboarding;
    private static EconomyLedger economy;
    private static MarketListingRepository listings;
    private static MarketPurchaseRepository purchases;

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
            statement.execute("TRUNCATE TABLE market_purchase, market_listing, economy_transaction, economy_account, audit_event, player_onboarding, player_identity CASCADE");
        }
    }

    @Test
    void purchaseAuditConflictRollsBackMoneyPurchaseAndListingTransition() throws Exception {
        PlayerIdentity seller = player("AtomicSeller");
        PlayerIdentity buyer = player("AtomicBuyer");
        MarketListing listed = listed(seller, 70L);
        credit(buyer, 100L, "atomic-paid-seed");
        reserveAuditKey(buyer.playerId(), "market:" + listed.listingId() + ":purchase-paid");

        MarketException exception = assertThrows(
                MarketException.class,
                () -> purchases.purchase(listed.listingNumber(), buyer.playerId(), UUID.randomUUID())
        );

        assertEquals(MarketException.Code.IDEMPOTENCY_CONFLICT, exception.code());
        assertEquals(100L, economy.balance(buyer.playerId(), "COINS"));
        assertEquals(0L, economy.balance(seller.playerId(), "COINS"));
        assertEquals(MarketListingStatus.LISTED,
                listings.findByNumber(listed.listingNumber()).orElseThrow().status());
        assertEquals(0L, countRows("market_purchase"));
        assertEquals(1L, countRows("economy_transaction")); // seed only
        assertNull(onboarding.find(buyer.playerId()).orElseThrow().marketUsedAt());
        assertNull(onboarding.find(seller.playerId()).orElseThrow().marketUsedAt());
    }

    @Test
    void completionAuditConflictRollsBackTerminalStateAndOnboardingMilestones() throws Exception {
        PlayerIdentity seller = player("CompleteAtomicSeller");
        PlayerIdentity buyer = player("CompleteAtomicBuyer");
        MarketListing listed = listed(seller, 60L);
        credit(buyer, 100L, "atomic-complete-seed");
        MarketPurchaseResult paid = purchases.purchase(
                listed.listingNumber(), buyer.playerId(), UUID.randomUUID()
        );
        reserveAuditKey(buyer.playerId(), "market:" + listed.listingId() + ":purchase-completed");

        MarketException exception = assertThrows(
                MarketException.class,
                () -> purchases.completeDelivery(listed.listingId(), buyer.playerId())
        );

        assertEquals(MarketException.Code.IDEMPOTENCY_CONFLICT, exception.code());
        MarketListing durableListing = purchases.findListing(listed.listingId()).orElseThrow();
        MarketPurchase durablePurchase = purchases.findByListing(listed.listingId()).orElseThrow();
        assertEquals(MarketListingStatus.DELIVERY_PENDING, durableListing.status());
        assertEquals(MarketPurchaseStatus.DELIVERY_PENDING, durablePurchase.status());
        assertNull(durablePurchase.completedAt());
        assertNull(onboarding.find(buyer.playerId()).orElseThrow().marketUsedAt());
        assertNull(onboarding.find(seller.playerId()).orElseThrow().marketUsedAt());
        assertEquals(40L, economy.balance(buyer.playerId(), "COINS"));
        assertEquals(60L, economy.balance(seller.playerId(), "COINS"));
        assertEquals(paid.purchase().purchaseId(), durablePurchase.purchaseId());
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
        onboarding = new OnboardingProgressRepository(database);
        AuditLog auditLog = new AuditLog();
        economy = new EconomyLedger(database, auditLog);
        listings = new MarketListingRepository(database, auditLog);
        purchases = new MarketPurchaseRepository(database, auditLog, economy, onboarding);
    }

    private static PlayerIdentity player(String name) {
        PlayerIdentity identity = identities.recordSeen(UUID.randomUUID(), name);
        onboarding.ensureStarted(identity.playerId());
        return identity;
    }

    private static MarketListing listed(PlayerIdentity seller, long price) {
        byte[] payload = {12, 34, 56};
        MarketListing prepared = listings.prepare(new MarketListingDraft(
                seller.playerId(),
                UUID.randomUUID(),
                payload,
                sha256(payload),
                "minecraft:emerald",
                1,
                "COINS",
                price,
                UUID.randomUUID()
        ));
        return listings.activatePrepared(prepared.listingId(), seller.playerId());
    }

    private static void credit(PlayerIdentity player, long amount, String key) {
        economy.apply(new EconomyMutation(
                player.playerId(),
                "COINS",
                amount,
                "test.market-seed",
                key + ":" + player.playerId(),
                UUID.randomUUID(),
                "{}"
        ));
    }

    private static void reserveAuditKey(UUID actorPlayerId, String idempotencyKey) throws Exception {
        try (var connection = database.connection();
             var statement = connection.prepareStatement("""
                     INSERT INTO audit_event (
                         event_id, actor_player_id, event_type, correlation_id,
                         idempotency_key, payload, occurred_at
                     ) VALUES (?, ?, 'test.market-conflict', ?, ?, '{}'::jsonb, NOW())
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, actorPlayerId);
            statement.setObject(3, UUID.randomUUID());
            statement.setString(4, idempotencyKey);
            statement.executeUpdate();
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

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
