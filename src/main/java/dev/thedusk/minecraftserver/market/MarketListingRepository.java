package dev.thedusk.minecraftserver.market;

import dev.thedusk.minecraftserver.audit.AuditLog;
import dev.thedusk.minecraftserver.persistence.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MarketListingRepository {
    private static final String INSERT_PREPARED_SQL = """
            INSERT INTO market_listing (
                listing_id, seller_player_id, status, escrow_token,
                item_payload, item_sha256, material_key, item_amount,
                currency_code, price_amount, prepare_correlation_id
            )
            VALUES (?, ?, 'PREPARED', ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (escrow_token) DO NOTHING
            RETURNING *
            """;

    private static final String FIND_BY_TOKEN_FOR_UPDATE_SQL = """
            SELECT * FROM market_listing
            WHERE escrow_token = ?
            FOR UPDATE
            """;

    private static final String FIND_BY_TOKEN_SQL = """
            SELECT * FROM market_listing
            WHERE escrow_token = ?
            """;

    private static final String FIND_BY_ID_FOR_UPDATE_SQL = """
            SELECT * FROM market_listing
            WHERE listing_id = ?
            FOR UPDATE
            """;

    private static final String FIND_BY_NUMBER_FOR_UPDATE_SQL = """
            SELECT * FROM market_listing
            WHERE listing_number = ?
            FOR UPDATE
            """;

    private static final String FIND_BY_NUMBER_SQL = """
            SELECT * FROM market_listing
            WHERE listing_number = ?
            """;

    private static final String FIND_OPEN_BY_SELLER_SQL = """
            SELECT * FROM market_listing
            WHERE seller_player_id = ?
              AND status IN ('PREPARED', 'LISTED', 'RETURN_PENDING')
            ORDER BY listing_number
            """;

    private static final String LIST_ACTIVE_SQL = """
            SELECT * FROM market_listing
            WHERE status = 'LISTED'
            ORDER BY listing_number
            LIMIT ?
            """;

    private static final String ACTIVATE_SQL = """
            UPDATE market_listing
            SET status = 'LISTED', listed_at = COALESCE(listed_at, NOW()),
                updated_at = NOW(), version = version + 1
            WHERE listing_id = ? AND status = 'PREPARED'
            RETURNING *
            """;

    private static final String REQUEST_RETURN_SQL = """
            UPDATE market_listing
            SET status = 'RETURN_PENDING', return_requested_at = NOW(),
                return_correlation_id = ?, updated_at = NOW(), version = version + 1
            WHERE listing_id = ? AND status = 'LISTED'
            RETURNING *
            """;

    private static final String COMPLETE_CANCEL_SQL = """
            UPDATE market_listing
            SET status = 'CANCELLED', cancelled_at = NOW(),
                updated_at = NOW(), version = version + 1
            WHERE listing_id = ? AND status = 'RETURN_PENDING'
            RETURNING *
            """;

    private final DatabaseManager database;
    private final AuditLog auditLog;

    public MarketListingRepository(DatabaseManager database, AuditLog auditLog) {
        this.database = Objects.requireNonNull(database, "database");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
    }

    public MarketListing prepare(MarketListingDraft draft) {
        Objects.requireNonNull(draft, "draft");
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                MarketListing inserted = insertPrepared(connection, draft);
                if (inserted != null) {
                    auditLog.append(
                            connection,
                            draft.sellerPlayerId(),
                            "market.listing_prepared",
                            draft.correlationId(),
                            auditKey(inserted.listingId(), "prepared"),
                            listingAuditPayload(inserted)
                    );
                    connection.commit();
                    return inserted;
                }

                MarketListing existing = requireByToken(connection, draft.escrowToken(), true);
                if (!samePreparation(existing, draft)) {
                    throw new MarketException(
                            MarketException.Code.IDEMPOTENCY_CONFLICT,
                            "Escrow token is already bound to a different listing preparation"
                    );
                }
                connection.commit();
                return existing;
            } catch (MarketException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to prepare market listing", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire market database connection", exception);
        }
    }

    public MarketListing activatePrepared(UUID listingId, UUID sellerPlayerId) {
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                MarketListing current = requireById(connection, listingId, true);
                requireOwner(current, sellerPlayerId);
                if (current.status() == MarketListingStatus.LISTED) {
                    connection.commit();
                    return current;
                }
                if (current.status() != MarketListingStatus.PREPARED) {
                    throw invalidState(current, "activate");
                }

                MarketListing updated = updateAndMap(connection, ACTIVATE_SQL, statement -> {
                    statement.setObject(1, listingId);
                });
                auditLog.append(
                        connection,
                        sellerPlayerId,
                        "market.listing_listed",
                        updated.prepareCorrelationId(),
                        auditKey(listingId, "listed"),
                        listingAuditPayload(updated)
                );
                connection.commit();
                return updated;
            } catch (MarketException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to activate market listing", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire market database connection", exception);
        }
    }

    public MarketListing requestReturn(long listingNumber, UUID sellerPlayerId, UUID correlationId) {
        Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (listingNumber <= 0) {
            throw new IllegalArgumentException("listingNumber must be positive");
        }

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                MarketListing current = requireByNumber(connection, listingNumber, true);
                requireOwner(current, sellerPlayerId);
                if (current.status() == MarketListingStatus.RETURN_PENDING
                        || current.status() == MarketListingStatus.CANCELLED) {
                    connection.commit();
                    return current;
                }
                if (current.status() != MarketListingStatus.LISTED) {
                    throw invalidState(current, "request return");
                }

                MarketListing updated = updateAndMap(connection, REQUEST_RETURN_SQL, statement -> {
                    statement.setObject(1, correlationId);
                    statement.setObject(2, current.listingId());
                });
                auditLog.append(
                        connection,
                        sellerPlayerId,
                        "market.listing_return_requested",
                        correlationId,
                        auditKey(current.listingId(), "return-requested"),
                        listingAuditPayload(updated)
                );
                connection.commit();
                return updated;
            } catch (MarketException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to request listing return", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire market database connection", exception);
        }
    }

    public MarketListing completeCancel(UUID listingId, UUID sellerPlayerId) {
        Objects.requireNonNull(listingId, "listingId");
        Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");

        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                MarketListing current = requireById(connection, listingId, true);
                requireOwner(current, sellerPlayerId);
                if (current.status() == MarketListingStatus.CANCELLED) {
                    connection.commit();
                    return current;
                }
                if (current.status() != MarketListingStatus.RETURN_PENDING) {
                    throw invalidState(current, "complete cancellation");
                }
                if (current.returnCorrelationId() == null) {
                    throw new MarketException(
                            MarketException.Code.INVALID_STATE,
                            "Return-pending listing has no correlation ID"
                    );
                }

                MarketListing updated = updateAndMap(connection, COMPLETE_CANCEL_SQL, statement -> {
                    statement.setObject(1, listingId);
                });
                auditLog.append(
                        connection,
                        sellerPlayerId,
                        "market.listing_cancelled",
                        updated.returnCorrelationId(),
                        auditKey(listingId, "cancelled"),
                        listingAuditPayload(updated)
                );
                connection.commit();
                return updated;
            } catch (MarketException exception) {
                rollbackQuietly(connection);
                throw exception;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw storageFailure("Failed to complete listing cancellation", exception);
            } catch (RuntimeException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to acquire market database connection", exception);
        }
    }

    public Optional<MarketListing> findByEscrowToken(UUID escrowToken) {
        Objects.requireNonNull(escrowToken, "escrowToken");
        return queryOptional(FIND_BY_TOKEN_SQL, statement -> statement.setObject(1, escrowToken));
    }

    public Optional<MarketListing> findByNumber(long listingNumber) {
        if (listingNumber <= 0) {
            return Optional.empty();
        }
        return queryOptional(FIND_BY_NUMBER_SQL, statement -> statement.setLong(1, listingNumber));
    }

    public List<MarketListing> findOpenBySeller(UUID sellerPlayerId) {
        Objects.requireNonNull(sellerPlayerId, "sellerPlayerId");
        return queryList(FIND_OPEN_BY_SELLER_SQL, statement -> statement.setObject(1, sellerPlayerId));
    }

    public List<MarketListing> listActive(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return queryList(LIST_ACTIVE_SQL, statement -> statement.setInt(1, limit));
    }

    private MarketListing insertPrepared(Connection connection, MarketListingDraft draft) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PREPARED_SQL)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, draft.sellerPlayerId());
            statement.setObject(3, draft.escrowToken());
            statement.setBytes(4, draft.itemPayload());
            statement.setString(5, draft.itemSha256());
            statement.setString(6, draft.materialKey());
            statement.setInt(7, draft.itemAmount());
            statement.setString(8, draft.currencyCode());
            statement.setLong(9, draft.priceAmount());
            statement.setObject(10, draft.correlationId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? map(result) : null;
            }
        }
    }

    private MarketListing requireByToken(Connection connection, UUID token, boolean forUpdate) throws SQLException {
        String sql = forUpdate ? FIND_BY_TOKEN_FOR_UPDATE_SQL : FIND_BY_TOKEN_SQL;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, token);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new MarketException(MarketException.Code.NOT_FOUND, "Market listing not found for escrow token");
                }
                return map(result);
            }
        }
    }

    private MarketListing requireById(Connection connection, UUID listingId, boolean forUpdate) throws SQLException {
        String sql = forUpdate ? FIND_BY_ID_FOR_UPDATE_SQL : "SELECT * FROM market_listing WHERE listing_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, listingId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new MarketException(MarketException.Code.NOT_FOUND, "Market listing not found: " + listingId);
                }
                return map(result);
            }
        }
    }

    private MarketListing requireByNumber(Connection connection, long listingNumber, boolean forUpdate)
            throws SQLException {
        String sql = forUpdate ? FIND_BY_NUMBER_FOR_UPDATE_SQL : FIND_BY_NUMBER_SQL;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, listingNumber);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new MarketException(
                            MarketException.Code.NOT_FOUND,
                            "Market listing not found: #" + listingNumber
                    );
                }
                return map(result);
            }
        }
    }

    private Optional<MarketListing> queryOptional(String sql, SqlBinder binder) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to query market listing", exception);
        }
    }

    private List<MarketListing> queryList(String sql, SqlBinder binder) {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                List<MarketListing> listings = new ArrayList<>();
                while (result.next()) {
                    listings.add(map(result));
                }
                return List.copyOf(listings);
            }
        } catch (SQLException exception) {
            throw storageFailure("Failed to query market listings", exception);
        }
    }

    private static MarketListing updateAndMap(Connection connection, String sql, SqlBinder binder)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Market state transition affected no row");
                }
                return map(result);
            }
        }
    }

    private static void requireOwner(MarketListing listing, UUID sellerPlayerId) {
        if (!listing.sellerPlayerId().equals(sellerPlayerId)) {
            throw new MarketException(MarketException.Code.NOT_OWNER, "Listing belongs to another player");
        }
    }

    private static boolean samePreparation(MarketListing listing, MarketListingDraft draft) {
        return listing.sellerPlayerId().equals(draft.sellerPlayerId())
                && listing.escrowToken().equals(draft.escrowToken())
                && Arrays.equals(listing.itemPayload(), draft.itemPayload())
                && listing.itemSha256().equals(draft.itemSha256())
                && listing.materialKey().equals(draft.materialKey())
                && listing.itemAmount() == draft.itemAmount()
                && listing.currencyCode().equals(draft.currencyCode())
                && listing.priceAmount() == draft.priceAmount();
    }

    private static MarketException invalidState(MarketListing listing, String operation) {
        return new MarketException(
                MarketException.Code.INVALID_STATE,
                "Cannot " + operation + " listing #" + listing.listingNumber() + " from " + listing.status()
        );
    }

    private static MarketListing map(ResultSet result) throws SQLException {
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

    private static String auditKey(UUID listingId, String transition) {
        return "market:listing:" + listingId + ":" + transition;
    }

    private static String listingAuditPayload(MarketListing listing) {
        return "{\"listingId\":\"" + listing.listingId()
                + "\",\"listingNumber\":" + listing.listingNumber()
                + ",\"status\":\"" + listing.status()
                + "\",\"material\":\"" + listing.materialKey()
                + "\",\"amount\":" + listing.itemAmount()
                + ",\"currency\":\"" + listing.currencyCode()
                + "\",\"price\":" + listing.priceAmount() + "}";
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

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
