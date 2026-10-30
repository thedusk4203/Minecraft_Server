# Market foundation — Phase 3C

## Decision

The MVP market keeps **payment authority in MinecraftServerCore/PostgreSQL** instead of adopting a Vault-first auction/shop plugin.

This is a transaction-boundary decision. PostgreSQL can atomically settle money between two wallets; a live Bukkit inventory cannot participate in that same database transaction. The market therefore combines:

- atomic PostgreSQL settlement for money;
- durable, retryable state machines for seller escrow and buyer delivery.

## Phase 3C1 — money settlement

Status: complete. PR #6 merged at `96f756f5e20caaf1151d484110c40e2b1bcfa243`; final-head CI `41003473192` succeeded.

`EconomyTransfer` settles payer and payee in one PostgreSQL transaction. Missing account creation and row locking both use deterministic player-ID ordering. Exact replay returns the original ledger identity without moving money again; partial or conflicting replay fails closed.

## Phase 3C2a — seller escrow and listing

Status: complete. PR #7 merged at `8ccfa391cdc1ef86194b9795f4fff3066d7d4b41`; final-head CI `41013473040` succeeded.

### Seller crash-ordering

Before PostgreSQL may claim that an item is escrowed, the live stack receives a PDC UUID token and the marked playerdata is saved. The durable listing snapshot stores Paper raw-NBT bytes plus SHA-256. Only after PREPARED exists is the marked stack removed; that removal is saved before LISTED may commit.

```text
mark item -> save playerdata -> PREPARED
PREPARED -> remove item -> save playerdata -> LISTED
```

Cancellation mirrors the ordering in reverse:

```text
LISTED -> RETURN_PENDING
restore marked snapshot -> save playerdata -> CANCELLED
strip marker -> best-effort save
```

The escrow token is therefore both a temporary item lock and the recovery identity for interrupted transitions.

## Phase 3C2b — purchase and buyer delivery

Status: in progress.

### Durable purchase model

V5 adds a separate `market_purchase` table rather than overloading the seller listing row with payment metadata. A listing still owns item/price/seller state; a purchase owns buyer/payment/delivery state.

Listing lifecycle:

```text
PREPARED -> LISTED -> RETURN_PENDING -> CANCELLED
                 \
                  -> DELIVERY_PENDING -> COMPLETED
```

Purchase lifecycle:

```text
DELIVERY_PENDING -> COMPLETED
```

A listing has at most one durable purchase row.

### Atomic payment boundary

The purchase path locks the listing row before money moves. If the row is still LISTED, the existing Phase 3C1 transfer primitive is composed inside the same PostgreSQL transaction:

```text
begin
  lock LISTED row
  reject seller == buyer
  transfer buyer -> seller using key market:purchase:<listing_id>
  insert market_purchase DELIVERY_PENDING with debit/credit transaction IDs
  LISTED -> DELIVERY_PENDING
  append market.purchase_paid audit
commit
```

Any failure after the transfer but before commit rolls back the debit, credit, purchase row, listing transition and audit together. A second buyer serializes behind the same listing row and cannot pay after the first buyer commits.

Exact retry by the winning buyer verifies the original debit/credit transaction IDs and returns the durable purchase without charging again. Another buyer fails closed.

### Buyer exactly-once delivery

Bukkit inventory still cannot participate in the payment transaction. Delivery therefore reuses the original escrow token as an acknowledgement marker.

```text
main thread
  if buyer does not already hold escrow token:
      deserialize stored raw-NBT snapshot
      verify SHA-256 + token
      place marked item in one empty slot
  player.saveData()                 # marked item durable first

persistence executor
  lock listing + purchase
  DELIVERY_PENDING -> COMPLETED
  mark buyer + seller market_used_at
  append market.purchase_completed audit
  commit

main thread
  strip escrow marker
  player.saveData() best effort
```

Crash cases remain recoverable:

- crash before the first buyer save: DB remains DELIVERY_PENDING and no durable item exists, so recovery may deliver it;
- crash after buyer save but before DB completion: recovery sees the token and acknowledges the existing item instead of minting another;
- crash after DB completion but before marker-strip save: recovery sees COMPLETED and strips the stale marker;
- full inventory: money stays durably settled and DELIVERY_PENDING remains until the buyer frees one slot and runs `/market recover` or reconnects.

Marked items remain protected by the Phase 3C2a escrow guard while delivery is unresolved.

### Seller/buyer recovery separation

The same token can now legitimately appear in the buyer inventory even though the listing belongs to a different seller. Seller recovery therefore treats markers for foreign listing owners as protected, not orphaned. Buyer recovery verifies that the durable `market_purchase.buyer_player_id` matches the current player before acknowledging or cleaning a delivery marker.

### Onboarding rule

Payment alone does **not** set `market_used_at`. The milestone is written for both buyer and seller only in the same transaction that moves listing and purchase state to COMPLETED.

Join guidance is durable-state driven:

- no land claim -> claim objective;
- land but no starter currency -> AuraSkills objective;
- currency but no market milestone -> `/market list` plus buy/sell guidance;
- market milestone present -> current onboarding loop complete.

### Failure injection requirements

Integration tests deliberately reserve the audit idempotency keys for `purchase-paid` and `purchase-completed`. These conflicts occur at the end of their respective transactions and must prove rollback of:

- buyer debit + seller credit;
- market purchase/listing transition;
- terminal onboarding milestone.

## Threading

Bukkit inventory access and `Player.saveData()` run on the server-owning thread. JDBC/Flyway/market repository work runs through the persistence executor. The explicit `saveData()` calls are limited to escrow/delivery boundaries where playerdata durability must precede a database terminal state.

## Non-goals for the MVP market

Do not add yet:

- bidding;
- sophisticated auction search;
- price-history UI;
- dynamic taxes/fees;
- cross-server distributed market services;
- Redis solely for market state;
- a Vault compatibility layer merely to reuse an external shop GUI.

The immediate goal remains one understandable, recoverable trade path that cannot duplicate money or items.
