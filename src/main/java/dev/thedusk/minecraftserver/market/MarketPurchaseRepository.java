package dev.thedusk.minecraftserver.market;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.economy.EconomyLedger;
import dev.thedusk.minecraftserver.economy.EconomyTransfer;
import dev.thedusk.minecraftserver.economy.EconomyTransferResult;
import dev.thedusk.minecraftserver.onboarding.OnboardingProgressRepository;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MarketPurchaseRepository {
    private static final String LOCK_LISTING_BY_NUMBER_SQL = """
            SELECT * FROM market_listing
            WHERE listing_number = ?
            FOR UPDATE
            """;

    private static final String LOCK_LISTING_BY_ID_SQL = """
            SELECT * FROM market_listing
            WHERE listing_id = ?
            FOR UPDATE
            """;

    private static final String FIND_LISTING_BY_ID_SQL = """
            SELECT * FROM market_listing
            WHERE listing_id = ?
            """;

    private static final String LOCK_PURCHASE_BY_LISTING_SQL = """
            SELECT * FROM market_purchase
            WHERE listing_id = ?
            FOR UPDATE
            """;

    private static final String FIND_PURCHASE_BY_LISTING_SQL = """
            SELECT * FROM market_purchase
            WHERE listing_id = ?
            """;

    private static final String FIND_PENDING_BY_BUYER_SQL = """
            SELECT * FROM market_purchase
            WHERE buyer_player_id = ? AND status = 'DELIVERY_PENDING'
            ORDER BY purchased_at, purchase_id
            """;

    private static final String INSERT_PURCHASE_SQL = """
            INSERT INTO market_purchase (
                purchase_id, listing_id, buyer_player_id, status,
                purchase_correlation_id, buyer_debit_transaction_id,
                seller_credit_transaction_id, purchased_at
            )
            VALUES (?, ?, ?, 'DELIVERY_PENDING', ?, ?, ?, NOW())
            RETURNING *
            """;

    private static final String MARK_LISTING_DELIVERY_PENDING_SQL = """
            UPDATE market_listing
            SET status = 'DELIVERY_PENDING', updated_at = NOW(), version = version + 1
            WHERE listing_id = ? AND status = 'LISTED'
            RETURNING *
            """;

    private static final String COMPLETE_PURCHASE_SQL = """
            UPDATE market_purchase
            SET status = 'COMPLETED', completed_at = NOW(),
                updated_at = NOW(), version = version + 1
            WHERE purchase_id = ? AND status = 'DELIVERY_PENDING'
            RETURNING *
            """;

    private static final String COMPLETE_LISTING_SQL = """
            UPDATE market_listing
            SET status = 'COMPLETED', updated_at = NOW(), version = version + 1
            WHERE listing_id = ? AND status = 'DELIVERY_PENDING'
            RETURNING *
            """;

    private final DatabaseManager database;
    private final AuditLog auditLog;
    private final EconomyLedger economy;
    private final OnboardingProgressRepository onboarding;

    public MarketPurchaseRepository(
            DatabaseManager database,
            AuditLog auditLog,
            EconomyLedger economy,
            OnboardingProgressRepository onboarding
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
    }

    public MarketPurchaseResult purchase(long listingNumber, UUID buyerPlayerId, UUID correlationId) {
        if (listingNumber <= 0) {
            throw new IllegalArgumentException("listingNumber must be positive");
        }
        Objects.requireNonNull(buyerPlayerId, "buyerPlayerId");
        Objects.requireNonNull(correlationId, "correlationId");

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                MarketListing listing = requireListingByNumber(connection, listingNumber);
                if (listing.sellerPlayerId().equals(buyerPlayerId)) {
                    throw new MarketException(
                            MarketException.Code.INVALID_STATE,
                            "Seller cannot purchase their own listing"
                    );
                }

                if (listing.status() == MarketListingStatus.DELIVERY_PENDING
                        || listing.status() == MarketListingStatus.COMPLETED) {
                    MarketPurchase existing = requirePurchaseByListing(connection, listing.listingId());
                    requireBuyer(existing, buyerPlayerId);
                    verifySettlementReplay(connection, listing, existing);
                    connection.commit();
                    return new MarketPurchaseResult(listing, existing, true);
                }
                if (listing.status() != MarketListingStatus.LISTED) {
                    throw new MarketException(
                            MarketException.Code.INVALID_STATE,
                            "Listing #" + listingNumber + " is not available for purchase"
                    );
                }

                EconomyTransferResult settlement = economy.transferInTransaction(
                        connection,
                        transfer(listing, buyerPlayerId, correlationId)
                );
                MarketPurchase purchase = insertPurchase(
                        connection,
                        listing,
                        buyerPlayerId,
                        settlement
                );
                MarketListing updatedListing = updateListing(
                        connection,
                        MARK_LISTING_DELIVERY_PENDING_SQL,
                        listing.listingId()
                );
                auditLog.append(
                        connection,
                        buyerPlayerId,
                        "market.purchase_paid",
                        settlement.correlationId(),
                        auditKey(listing.listingId(), "purchase-paid"),
                        purchaseAuditPayload(updatedListing, purchase)
                );
                connection.commit();
                return new MarketPurchaseResult(updatedListing, purchase, settlement.idempotentReplay());
            } catch (MarketException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw sqlFailure("Failed to settle market purchase", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire market purchase connection", exception);
        }
    }

    public MarketPurchaseResult completeDelivery(UUID listingId, UUID buyerPlayerId) {
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(buyerPlayerId, "buyerPlayerId");

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                MarketListing listing = requireListingById(connection, listingId);
                MarketPurchase purchase = requirePurchaseByListing(connection, listingId);
                requireBuyer(purchase, buyerPlayerId);

                if (listing.status() == MarketListingStatus.COMPLETED
                        && purchase.status() == MarketPurchaseStatus.COMPLETED) {
                    connection.commit();
                    return new MarketPurchaseResult(listing, purchase, true);
                }
                if (listing.status() != MarketListingStatus.DELIVERY_PENDING
                        || purchase.status() != MarketPurchaseStatus.DELIVERY_PENDING) {
                    throw new MarketException(
                            MarketException.Code.INVALID_STATE,
                            "Market purchase is not awaiting delivery"
                    );
                }

                MarketPurchase completedPurchase = updatePurchaseComplete(connection, purchase.purchaseId());
                MarketListing completedListing = updateListing(
                        connection,
                        COMPLETE_LISTING_SQL,
                        listing.listingId()
                );

                markParticipantsUsedMarket(connection, buyerPlayerId, listing.sellerPlayerId());
                auditLog.append(
                        connection,
                        buyerPlayerId,
                        "market.purchase_completed",
                        purchase.purchaseCorrelationId(),
                        auditKey(listing.listingId(), "purchase-completed"),
                        purchaseAuditPayload(completedListing, completedPurchase)
                );
                connection.commit();
                return new MarketPurchaseResult(completedListing, completedPurchase, false);
            } catch (MarketException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw sqlFailure("Failed to complete market delivery", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire market delivery connection", exception);
        }
    }

    public Optional<MarketPurchase> findByListing(UUID listingId) {
        Objects.requireNonNull(listingId, "listingId");
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(FIND_PURCHASE_BY_LISTING_SQL)) {
            statement.setObject(1, listingId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapPurchase(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to query market purchase", exception);
        }
    }

    public Optional<MarketListing> findListing(UUID listingId) {
        Objects.requireNonNull(listingId, "listingId");
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(FIND_LISTING_BY_ID_SQL)) {
            statement.setObject(1, listingId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapListing(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to query purchased listing", exception);
        }
    }

    public List<MarketPurchase> findPendingByBuyer(UUID buyerPlayerId) {
        Objects.requireNonNull(buyerPlayerId, "buyerPlayerId");
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(FIND_PENDING_BY_BUYER_SQL)) {
            statement.setObject(1, buyerPlayerId);
            try (ResultSet result = statement.executeQuery()) {
                List<MarketPurchase> purchases = new ArrayList<>();
                while (result.next()) {
                    purchases.add(mapPurchase(result));
                }
                return List.copyOf(purchases);
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to query pending market deliveries", exception);
        }
    }

    private MarketListing requireListingByNumber(Connection connection, long listingNumber) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_LISTING_BY_NUMBER_SQL)) {
            statement.setLong(1, listingNumber);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new MarketException(MarketException.Code.NOT_FOUND, "Market listing not found: #" + listingNumber);
                }
                return mapListing(result);
            }
        }
    }

    private MarketListing requireListingById(Connection connection, UUID listingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_LISTING_BY_ID_SQL)) {
            statement.setObject(1, listingId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new MarketException(MarketException.Code.NOT_FOUND, "Market listing not found: " + listingId);
                }
                return mapListing(result);
            }
        }
    }

    private MarketPurchase requirePurchaseByListing(Connection connection, UUID listingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_PURCHASE_BY_LISTING_SQL)) {
            statement.setObject(1, listingId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new MarketException(
                            MarketException.Code.INVALID_STATE,
                            "Purchased listing is missing its durable purchase row"
                    );
                }
                return mapPurchase(result);
            }
        }
    }

    private void verifySettlementReplay(
            Connection connection,
            MarketListing listing,
            MarketPurchase purchase
    ) {
        EconomyTransferResult replay = economy.transferInTransaction(
                connection,
                transfer(listing, purchase.buyerPlayerId(), purchase.purchaseCorrelationId())
        );
        if (!replay.debitTransactionId().equals(purchase.buyerDebitTransactionId())
                || !replay.creditTransactionId().equals(purchase.sellerCreditTransactionId())) {
            throw new MarketException(
                    MarketException.Code.IDEMPOTENCY_CONFLICT,
                    "Market purchase ledger identity does not match durable purchase state"
            );
        }
    }

    private MarketPurchase insertPurchase(
            Connection connection,
            MarketListing listing,
            UUID buyerPlayerId,
            EconomyTransferResult settlement
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PURCHASE_SQL)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, listing.listingId());
            statement.setObject(3, buyerPlayerId);
            statement.setObject(4, settlement.correlationId());
            statement.setObject(5, settlement.debitTransactionId());
            statement.setObject(6, settlement.creditTransactionId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Market purchase insert returned no row");
                }
                return mapPurchase(result);
            }
        }
    }

    private static MarketListing updateListing(Connection connection, String sql, UUID listingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, listingId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Market listing transition affected no row");
                }
                return mapListing(result);
            }
        }
    }

    private static MarketPurchase updatePurchaseComplete(Connection connection, UUID purchaseId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(COMPLETE_PURCHASE_SQL)) {
            statement.setObject(1, purchaseId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Market purchase completion affected no row");
                }
                return mapPurchase(result);
            }
        }
    }

    private void markParticipantsUsedMarket(Connection connection, UUID buyer, UUID seller) throws SQLException {
        UUID first = buyer.compareTo(seller) <= 0 ? buyer : seller;
        UUID second = first.equals(buyer) ? seller : buyer;
        onboarding.markMarketUsedInTransaction(connection, first);
        onboarding.markMarketUsedInTransaction(connection, second);
    }

    private static void requireBuyer(MarketPurchase purchase, UUID buyerPlayerId) {
        if (!purchase.buyerPlayerId().equals(buyerPlayerId)) {
            throw new MarketException(
                    MarketException.Code.INVALID_STATE,
                    "Listing was already purchased by another player"
            );
        }
    }

    private static EconomyTransfer transfer(
            MarketListing listing,
            UUID buyerPlayerId,
            UUID correlationId
    ) {
        return new EconomyTransfer(
                buyerPlayerId,
                listing.sellerPlayerId(),
                listing.currencyCode(),
                listing.priceAmount(),
                "market.purchase",
                "market:purchase:" + listing.listingId(),
                correlationId,
                "{\"listingId\":\"" + listing.listingId()
                        + "\",\"listingNumber\":" + listing.listingNumber() + "}"
        );
    }

    private static String auditKey(UUID listingId, String stage) {
        return "market:" + listingId + ":" + stage;
    }

    private static String purchaseAuditPayload(MarketListing listing, MarketPurchase purchase) {
        return "{\"listingId\":\"" + listing.listingId()
                + "\",\"listingNumber\":" + listing.listingNumber()
                + ",\"sellerPlayerId\":\"" + listing.sellerPlayerId()
                + "\",\"buyerPlayerId\":\"" + purchase.buyerPlayerId()
                + "\",\"currency\":\"" + listing.currencyCode()
                + "\",\"price\":" + listing.priceAmount()
                + ",\"purchaseId\":\"" + purchase.purchaseId() + "\"}";
    }

    private static MarketPurchase mapPurchase(ResultSet result) throws SQLException {
        return new MarketPurchase(
                result.getObject("purchase_id", UUID.class),
                result.getObject("listing_id", UUID.class),
                result.getObject("buyer_player_id", UUID.class),
                MarketPurchaseStatus.valueOf(result.getString("status")),
                result.getObject("purchase_correlation_id", UUID.class),
                result.getObject("buyer_debit_transaction_id", UUID.class),
                result.getObject("seller_credit_transaction_id", UUID.class),
                instant(result, "purchased_at"),
                instant(result, "completed_at"),
                result.getLong("version")
        );
    }

    private static MarketListing mapListing(ResultSet result) throws SQLException {
        return new MarketListing(
                result.getObject("listing_id", UUID.class),
                result.getLong("listing_number"),
                result.getObject("seller_player_id", UUID.class),
                MarketListingStatus.valueOf(result.getString("status")),
                result.getObject("escrow_token", UUID.class),
                result.getBytes("item_payload"),
                result.getString("item_sha256"),
                result.getString("material_key"),
                result.getInt("item_amount"),
                result.getString("currency_code"),
                result.getLong("price_amount"),
                result.getObject("prepare_correlation_id", UUID.class),
                result.getObject("return_correlation_id", UUID.class),
                instant(result, "created_at"),
                instant(result, "listed_at"),
                instant(result, "return_requested_at"),
                instant(result, "cancelled_at"),
                result.getLong("version")
        );
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static MarketException sqlFailure(String message, SQLException exception) {
        if ("23505".equals(exception.getSQLState())) {
            return new MarketException(
                    MarketException.Code.IDEMPOTENCY_CONFLICT,
                    "Market purchase uniqueness invariant was violated",
                    exception
            );
        }
        return storageFailure(message, exception);
    }

    private static MarketException storageFailure(String message, SQLException exception) {
        return new MarketException(MarketException.Code.STORAGE_FAILURE, message, exception);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original failure.
        }
    }
}
