package dev.thedusk.minecraftserver.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.economy.EconomyException;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.economy.EconomyMutation;
import dev.thedusk.minecraftserver.identity.PlayerIdentity;
import dev.thedusk.minecraftserver.identity.PlayerIdentityRepository;
import dev.thedusk.minecraftserver.onboarding.OnboardingProgressRepository;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import dev.thedusk.minecraftserver.persistence.DatabaseSettings;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class MarketPurchaseRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("minecraft_purchase_test")
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
    void purchaseAtomicallyMovesMoneyAndLocksListingForDelivery() throws Exception {
        PlayerIdentity seller = player("SellerA");
        PlayerIdentity buyer = player("BuyerA");
        MarketListing listed = listed(seller, 75L);
        credit(buyer, 100L, "purchase-a-seed");

        MarketPurchaseResult result = purchases.purchase(
                listed.listingNumber(), buyer.playerId(), UUID.randomUUID()
        );

        assertFalse(result.idempotentReplay());
        assertEquals(MarketListingStatus.DELIVERY_PENDING, result.listing().status());
        assertEquals(MarketPurchaseStatus.DELIVERY_PENDING, result.purchase().status());
        assertEquals(buyer.playerId(), result.purchase().buyerPlayerId());
        assertEquals(25L, economy.balance(buyer.playerId(), "COINS"));
        assertEquals(75L, economy.balance(seller.playerId(), "COINS"));
        assertEquals(3L, countRows("economy_transaction")); // seed + debit + credit
        assertEquals(1L, countRows("market_purchase"));
        assertEquals(1L, countAudit("market.purchase_paid"));
        assertNull(onboarding.find(buyer.playerId()).orElseThrow().marketUsedAt());
        assertNull(onboarding.find(seller.playerId()).orElseThrow().marketUsedAt());
    }

    @Test
    void exactPurchaseReplayDoesNotChargeTwice() throws Exception {
        PlayerIdentity seller = player("SellerReplay");
        PlayerIdentity buyer = player("BuyerReplay");
        MarketListing listed = listed(seller, 40L);
        credit(buyer, 100L, "replay-seed");

        MarketPurchaseResult first = purchases.purchase(
                listed.listingNumber(), buyer.playerId(), UUID.randomUUID()
        );
        MarketPurchaseResult replay = purchases.purchase(
                listed.listingNumber(), buyer.playerId(), UUID.randomUUID()
        );

        assertFalse(first.idempotentReplay());
        assertTrue(replay.idempotentReplay());
        assertEquals(first.purchase().purchaseId(), replay.purchase().purchaseId());
        assertEquals(first.purchase().buyerDebitTransactionId(), replay.purchase().buyerDebitTransactionId());
        assertEquals(first.purchase().sellerCreditTransactionId(), replay.purchase().sellerCreditTransactionId());
        assertEquals(60L, economy.balance(buyer.playerId(), "COINS"));
        assertEquals(40L, economy.balance(seller.playerId(), "COINS"));
        assertEquals(3L, countRows("economy_transaction"));
        assertEquals(1L, countAudit("market.purchase_paid"));
    }

    @Test
    void secondBuyerCannotTakeAlreadyPurchasedListing() {
        PlayerIdentity seller = player("SellerRaceSerial");
        PlayerIdentity buyerOne = player("BuyerOne");
        PlayerIdentity buyerTwo = player("BuyerTwo");
        MarketListing listed = listed(seller, 30L);
        credit(buyerOne, 100L, "buyer-one-seed");
        credit(buyerTwo, 100L, "buyer-two-seed");
        purchases.purchase(listed.listingNumber(), buyerOne.playerId(), UUID.randomUUID());

        MarketException exception = assertThrows(
                MarketException.class,
                () -> purchases.purchase(listed.listingNumber(), buyerTwo.playerId(), UUID.randomUUID())
        );

        assertEquals(MarketException.Code.INVALID_STATE, exception.code());
        assertEquals(70L, economy.balance(buyerOne.playerId(), "COINS"));
        assertEquals(100L, economy.balance(buyerTwo.playerId(), "COINS"));
        assertEquals(30L, economy.balance(seller.playerId(), "COINS"));
    }

    @Test
    void selfPurchaseFailsBeforeMoneyMoves() {
        PlayerIdentity seller = player("SelfSeller");
        MarketListing listed = listed(seller, 20L);
        credit(seller, 100L, "self-seed");

        MarketException exception = assertThrows(
                MarketException.class,
                () -> purchases.purchase(listed.listingNumber(), seller.playerId(), UUID.randomUUID())
        );

        assertEquals(MarketException.Code.INVALID_STATE, exception.code());
        assertEquals(100L, economy.balance(seller.playerId(), "COINS"));
        assertEquals(MarketListingStatus.LISTED, listings.findByNumber(listed.listingNumber()).orElseThrow().status());
        assertEquals(0L, countRowsUnchecked("market_purchase"));
    }

    @Test
    void insufficientFundsLeavesListingAvailableAndNoPurchaseRow() {
        PlayerIdentity seller = player("PoorSeller");
        PlayerIdentity buyer = player("PoorBuyer");
        MarketListing listed = listed(seller, 90L);
        credit(buyer, 50L, "poor-seed");

        EconomyException exception = assertThrows(
                EconomyException.class,
                () -> purchases.purchase(listed.listingNumber(), buyer.playerId(), UUID.randomUUID())
        );

        assertEquals(EconomyException.Code.INSUFFICIENT_FUNDS, exception.code());
        assertEquals(50L, economy.balance(buyer.playerId(), "COINS"));
        assertEquals(0L, economy.balance(seller.playerId(), "COINS"));
        assertEquals(MarketListingStatus.LISTED, listings.findByNumber(listed.listingNumber()).orElseThrow().status());
        assertEquals(0L, countRowsUnchecked("market_purchase"));
        assertEquals(0L, countAuditUnchecked("market.purchase_paid"));
    }

    @Test
    void completingDeliveryMarksBothParticipantsMarketUsedExactlyOnce() throws Exception {
        PlayerIdentity seller = player("CompleteSeller");
        PlayerIdentity buyer = player("CompleteBuyer");
        MarketListing listed = listed(seller, 50L);
        credit(buyer, 100L, "complete-seed");
        MarketPurchaseResult paid = purchases.purchase(
                listed.listingNumber(), buyer.playerId(), UUID.randomUUID()
        );

        MarketPurchaseResult completed = purchases.completeDelivery(
                paid.listing().listingId(), buyer.playerId()
        );
        MarketPurchaseResult replay = purchases.completeDelivery(
                paid.listing().listingId(), buyer.playerId()
        );

        assertFalse(completed.idempotentReplay());
        assertTrue(replay.idempotentReplay());
        assertEquals(MarketListingStatus.COMPLETED, completed.listing().status());
        assertEquals(MarketPurchaseStatus.COMPLETED, completed.purchase().status());
        assertNotNull(completed.purchase().completedAt());
        assertNotNull(onboarding.find(buyer.playerId()).orElseThrow().marketUsedAt());
        assertNotNull(onboarding.find(seller.playerId()).orElseThrow().marketUsedAt());
        assertEquals(1L, countAudit("market.purchase_completed"));
    }

    @Test
    void restartPreservesPaidDeliveryPendingStateAndBalances() {
        PlayerIdentity seller = player("RestartSellerPurchase");
        PlayerIdentity buyer = player("RestartBuyerPurchase");
        MarketListing listed = listed(seller, 65L);
        credit(buyer, 100L, "restart-purchase-seed");
        MarketPurchaseResult paid = purchases.purchase(
                listed.listingNumber(), buyer.playerId(), UUID.randomUUID()
        );

        database.close();
        openServices();

        MarketPurchase durable = purchases.findByListing(paid.listing().listingId()).orElseThrow();
        MarketListing durableListing = purchases.findListing(paid.listing().listingId()).orElseThrow();
        assertEquals(MarketPurchaseStatus.DELIVERY_PENDING, durable.status());
        assertEquals(MarketListingStatus.DELIVERY_PENDING, durableListing.status());
        assertEquals(35L, economy.balance(buyer.playerId(), "COINS"));
        assertEquals(65L, economy.balance(seller.playerId(), "COINS"));
        assertEquals(1, purchases.findPendingByBuyer(buyer.playerId()).size());
    }

    @Test
    void concurrentBuyersSerializeSoExactlyOneWins() throws Exception {
        PlayerIdentity seller = player("ConcurrentSeller");
        PlayerIdentity buyerOne = player("ConcurrentOne");
        PlayerIdentity buyerTwo = player("ConcurrentTwo");
        MarketListing listed = listed(seller, 45L);
        credit(buyerOne, 100L, "concurrent-one-seed");
        credit(buyerTwo, 100L, "concurrent-two-seed");

        List<Attempt> attempts = purchaseConcurrently(
                listed.listingNumber(), buyerOne.playerId(), buyerTwo.playerId()
        );

        assertEquals(1L, attempts.stream().filter(Attempt::success).count());
        assertEquals(1L, attempts.stream().filter(attempt -> !attempt.success()).count());
        assertEquals(45L, economy.balance(seller.playerId(), "COINS"));
        long buyerOneBalance = economy.balance(buyerOne.playerId(), "COINS");
        long buyerTwoBalance = economy.balance(buyerTwo.playerId(), "COINS");
        assertTrue((buyerOneBalance == 55L && buyerTwoBalance == 100L)
                || (buyerOneBalance == 100L && buyerTwoBalance == 55L));
        assertEquals(1L, countRows("market_purchase"));
        assertEquals(1L, countAudit("market.purchase_paid"));
        assertEquals(MarketListingStatus.DELIVERY_PENDING,
                listings.findByNumber(listed.listingNumber()).orElseThrow().status());
    }

    private static List<Attempt> purchaseConcurrently(
            long listingNumber,
            UUID buyerOne,
            UUID buyerTwo
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>();
        try {
            for (UUID buyer : List.of(buyerOne, buyerTwo)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for market purchase barrier");
                    }
                    try {
                        purchases.purchase(listingNumber, buyer, UUID.randomUUID());
                        return new Attempt(buyer, true, null);
                    } catch (RuntimeException exception) {
                        return new Attempt(buyer, false, exception);
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(10, TimeUnit.SECONDS));
            }
            return attempts;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static void openServices() {
        database = DatabaseManager.open(new DatabaseSettings(
                POSTGRES.getHost(),
                POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                POSTGRES.getDatabaseName(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                6
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
        byte[] payload = {42, 11, 7, 99};
        MarketListing prepared = listings.prepare(new MarketListingDraft(
                seller.playerId(),
                UUID.randomUUID(),
                payload,
                sha256(payload),
                "minecraft:diamond",
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

    private static long countRowsUnchecked(String table) {
        try {
            return countRows(table);
        } catch (Exception exception) {
            throw new AssertionError(exception);
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

    private static long countAuditUnchecked(String eventType) {
        try {
            return countAudit(eventType);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record Attempt(UUID buyerPlayerId, boolean success, RuntimeException failure) {
    }
}
