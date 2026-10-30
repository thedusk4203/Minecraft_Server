CREATE TABLE market_listing (
    listing_id UUID PRIMARY KEY,
    listing_number BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    seller_player_id UUID NOT NULL REFERENCES player_identity(player_id) ON DELETE RESTRICT,
    status VARCHAR(24) NOT NULL,
    escrow_token UUID NOT NULL UNIQUE,
    item_payload BYTEA NOT NULL,
    item_sha256 CHAR(64) NOT NULL,
    material_key VARCHAR(128) NOT NULL,
    item_amount INTEGER NOT NULL,
    currency_code VARCHAR(32) NOT NULL,
    price_amount BIGINT NOT NULL,
    prepare_correlation_id UUID NOT NULL,
    return_correlation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    listed_at TIMESTAMPTZ,
    return_requested_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT market_listing_status_chk
        CHECK (status IN ('PREPARED', 'LISTED', 'RETURN_PENDING', 'CANCELLED')),
    CONSTRAINT market_listing_item_hash_chk
        CHECK (item_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT market_listing_item_amount_chk
        CHECK (item_amount > 0),
    CONSTRAINT market_listing_currency_chk
        CHECK (currency_code ~ '^[A-Z0-9_]{1,32}$'),
    CONSTRAINT market_listing_price_chk
        CHECK (price_amount > 0),
    CONSTRAINT market_listing_listed_at_chk
        CHECK (status <> 'LISTED' OR listed_at IS NOT NULL),
    CONSTRAINT market_listing_return_requested_chk
        CHECK (status <> 'RETURN_PENDING' OR (return_requested_at IS NOT NULL AND return_correlation_id IS NOT NULL)),
    CONSTRAINT market_listing_cancelled_at_chk
        CHECK (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND return_correlation_id IS NOT NULL))
);

CREATE INDEX market_listing_status_number_idx
    ON market_listing (status, listing_number);

CREATE INDEX market_listing_seller_status_idx
    ON market_listing (seller_player_id, status, listing_number);
