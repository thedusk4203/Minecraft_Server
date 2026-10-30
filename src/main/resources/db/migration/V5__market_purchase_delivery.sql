ALTER TABLE market_listing
    DROP CONSTRAINT market_listing_status_chk;

ALTER TABLE market_listing
    ADD CONSTRAINT market_listing_status_chk
        CHECK (status IN (
            'PREPARED', 'LISTED', 'RETURN_PENDING', 'CANCELLED',
            'DELIVERY_PENDING', 'COMPLETED'
        ));

CREATE TABLE market_purchase (
    purchase_id UUID PRIMARY KEY,
    listing_id UUID NOT NULL UNIQUE REFERENCES market_listing(listing_id) ON DELETE RESTRICT,
    buyer_player_id UUID NOT NULL REFERENCES player_identity(player_id) ON DELETE RESTRICT,
    status VARCHAR(24) NOT NULL,
    purchase_correlation_id UUID NOT NULL,
    buyer_debit_transaction_id UUID NOT NULL UNIQUE REFERENCES economy_transaction(transaction_id) ON DELETE RESTRICT,
    seller_credit_transaction_id UUID NOT NULL UNIQUE REFERENCES economy_transaction(transaction_id) ON DELETE RESTRICT,
    purchased_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT market_purchase_status_chk
        CHECK (status IN ('DELIVERY_PENDING', 'COMPLETED')),
    CONSTRAINT market_purchase_completed_at_chk
        CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
);

CREATE INDEX market_purchase_buyer_status_idx
    ON market_purchase (buyer_player_id, status, purchased_at);
