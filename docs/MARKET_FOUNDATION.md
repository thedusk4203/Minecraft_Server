# Market foundation — Phase 3C

## Decision

The MVP market keeps **payment authority in MinecraftServerCore/PostgreSQL** instead of adopting a Vault-first auction/shop plugin.

This is a transaction-boundary decision. PostgreSQL can atomically settle money between two wallets; a live Bukkit inventory cannot participate in that same database transaction. The market therefore combines:

- atomic PostgreSQL settlement for money;
- durable, retryable state machines for item escrow and delivery.

## Phase 3C1 — money settlement

Status: complete. PR #6 merged at `96f756f5e20caaf1151d484110c40e2b1bcfa243`; final-head CI `41003473192` succeeded.

`EconomyTransfer` settles payer and payee in one PostgreSQL transaction:

```text
begin
  create missing account rows in deterministic player_id order
  lock both account rows in deterministic player_id order
  validate/replay transfer
  write payer debit ledger row
  write payee credit ledger row
  write balance-change audits
  write transfer audit
commit
```

The external transfer idempotency key derives `<key>:debit` and `<key>:credit`. Correct replay returns the original transaction IDs/correlation ID without moving money again. Partial or semantically conflicting replay fails closed.

## Phase 3C2a — seller escrow and listing

Status: in progress.

### Why the escrow token exists

Before PostgreSQL may claim that an item is in escrow, the live stack receives an internal PDC UUID marker. That marker is a temporary lock and recovery identity; it is never intended as player-facing item metadata.

The marker allows reconnect recovery to distinguish:

- an item still present in the seller inventory;
- an item already removed into escrow;
- a returned item whose database acknowledgement has not committed yet;
- an orphan marker with no durable listing.

### Item snapshot

The listing stores Paper's raw-NBT `ItemStack.serializeAsBytes()` payload plus SHA-256. The raw NBT includes Minecraft DataVersion metadata and `deserializeBytes()` can apply Minecraft's data converter when loading under a newer version.

The durable row also stores material key, amount, currency and price as query/debug metadata. The raw payload remains authoritative for reconstruction.

### Seller create flow

The ordering is deliberate:

```text
main thread
  clone held stack
  add escrow token PDC
  write marked stack back to inventory
  serialize marked stack
  player.saveData()                 # marker durable first

persistence executor
  insert PREPARED listing + audit   # idempotent by escrow_token

main thread
  remove marked stack from inventory
  player.saveData()                 # removal durable before LISTED

persistence executor
  PREPARED -> LISTED + audit
```

This ordering closes the dangerous crash windows:

- crash before PREPARED: at worst a marked orphan exists; recovery removes the marker because no listing exists;
- crash after PREPARED but before removal: durable playerdata still contains the marked stack; recovery removes it and activates the listing;
- crash after removal but before LISTED: durable PREPARED snapshot exists and durable playerdata no longer has the item; recovery can safely activate LISTED;
- once LISTED is durable, seller playerdata has already durably lost the escrow item.

### Escrow guard

While the PDC marker exists, player-driven mutation is blocked. Guarded paths include inventory click/drag/hotbar swap, dropping, hand swapping, interaction, placement, consumption, durability damage, mending and attacks with the marked item.

On death, a marked stack is removed from `PlayerDeathEvent#getDrops()` and added to `getItemsToKeep()` so it cannot become a world drop while the saga is incomplete.

### Cancellation / return flow

Cancellation also avoids making a terminal DB state before item persistence:

```text
persistence executor
  LISTED -> RETURN_PENDING
  store return correlation ID + audit

main thread
  reconstruct marked item from stored NBT if not already present
  place into an empty inventory slot
  verify SHA-256 and escrow token
  player.saveData()                 # returned marked item durable

persistence executor
  RETURN_PENDING -> CANCELLED + audit

main thread
  strip internal escrow marker
  player.saveData() best effort
```

If the final marker-strip save is interrupted, correctness is preserved: the item itself was already durable before `CANCELLED`; reconnect recovery sees the terminal listing and strips the stale marker.

### Commit ambiguity

A timeout/failure after a database call is not assumed to mean rollback. Recovery re-reads the durable row before taking compensating action.

Examples:

- PREPARED call reports failure → query by escrow token; if row exists continue escrow, otherwise unlock item;
- cancellation request reports failure → re-read listing and verify seller ownership before any return action;
- cancellation acknowledgement reports failure → leave returned item marked; reconnect recovery determines whether row is still RETURN_PENDING or already CANCELLED.

### Listing state in 3C2a

```text
PREPARED -> LISTED -> RETURN_PENDING -> CANCELLED
```

`PREPARED` is not visible to buyers. Only `LISTED` appears in `/market list`.

`market_used_at` is intentionally untouched because seller listing preparation is not yet a completed player-to-player trade.

## Phase 3C2b — purchase and buyer delivery

Planned state will extend or complement listing state with buyer reservation/payment/delivery identity. Required invariants:

- one listing can settle to at most one buyer;
- payment uses Phase 3C1 transfer and is idempotent;
- a paid order never becomes buyable again;
- buyer delivery is retryable and explicitly acknowledged;
- payment and item delivery can survive disconnect/restart between them;
- `market_used_at` is recorded only after terminal successful delivery.

## Threading

Bukkit inventory access and `Player.saveData()` run on the server-owning thread. JDBC/Flyway/market repository work runs through the persistence executor. The explicit `saveData()` calls are intentionally limited to escrow transition boundaries where playerdata durability must precede a database terminal state; they are not a general persistence mechanism.

## Non-goals for the MVP market

Do not add yet:

- bidding;
- sophisticated auction search;
- price-history UI;
- dynamic taxes/fees;
- cross-server distributed market services;
- Redis solely for market state;
- a Vault compatibility layer merely to reuse an external shop GUI.

The immediate goal is one understandable, recoverable trade path that cannot duplicate money or items.
