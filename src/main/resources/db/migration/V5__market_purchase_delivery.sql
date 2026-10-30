ALTER TABLE market_listing
    DROP CONSTRAINT market_listing_status_chk;

ALTER TABLE market_listing
    ADD COLUMN buyer_player_id UUID REFERENCES player_identity(player_id) ON DELETE RESTRICT,
    ADD COLUMN purchase_correlation_id UUID,
    ADD COLUMN buyer_debit_transaction_id UUID REFERENCES economy_transaction(transaction_id) ON DELETE RESTRICT,
    ADD COLUMN seller_credit_transaction_id UUID REFERENCES economy_transaction(transaction_id) ON DELETE RESTRICT,
    ADD COLUMN purchased_at TIMESTAMPTZ,
    ADD COLUMN completed_at TIMESTAMPTZ;

ALTER TABLE market_listing
    ADD CONSTRAINT market_listing_status_chk
        CHECK (status IN (
            'PREPARED', 'LISTED', 'RETURN_PENDING', 'CANCELLED',
            'DELIVERY_PENDING', 'COMPLETED'
        )),
    ADD CONSTRAINT market_listing_buyer_not_seller_chk
        CHECK (buyer_player_id IS NULL OR buyer_player_id <> seller_player_id),
    ADD CONSTRAINT market_listing_purchase_state_chk
        CHECK (
            status NOT IN ('DELIVERY_PENDING', 'COMPLETED')
            OR (
                buyer_player_id IS NOT NULL
                AND purchase_correlation_id IS NOT NULL
                AND buyer_debit_transaction_id IS NOT NULL
                AND seller_credit_transaction_id IS NOT NULL
                AND purchased_at IS NOT NULL
            )
        ),
    ADD CONSTRAINT market_listing_completed_at_chk
        CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL),
    ADD CONSTRAINT market_listing_buyer_debit_unique
        UNIQUE (buyer_debit_transaction_id),
    ADD CONSTRAINT market_listing_seller_credit_unique
        UNIQUE (seller_credit_transaction_id);

CREATE INDEX market_listing_buyer_status_idx
    ON market_listing (buyer_player_id, status, listing_number)
    WHERE buyer_player_id IS NOT NULL;
