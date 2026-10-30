# Market foundation — Phase 3C

## Decision

The MVP market will keep **payment authority in MinecraftServerCore/PostgreSQL** instead of adopting a Vault-first auction/shop plugin.

This is not a rejection of external plugins in general. It is a transaction-boundary decision: the existing economy ledger is PostgreSQL-authoritative, idempotent and audited, while typical Vault economy callbacks are synchronous and do not provide a transaction that can compose with the server's durable onboarding/market state.

## The two different atomicity problems

### Money ↔ money

Two PostgreSQL wallet balances can be settled atomically because both are in the same database transaction.

Phase 3C1 therefore provides `EconomyTransfer`:

```text
begin
  ensure payer account
  ensure payee account
  lock both accounts in deterministic player_id order
  validate/replay transfer
  write payer debit ledger row
  write payee credit ledger row
  write balance-change audits
  write transfer audit
commit
```

If any step fails, both balances and every new audit/ledger row roll back together.

### Item ↔ money

A live Bukkit inventory is not a PostgreSQL table. Removing or adding an item cannot participate in the database commit above.

Therefore Phase 3C2 must use a **durable workflow/state machine**, not a fake cross-system transaction. The invariant is eventual exactly-once value delivery under retry, not simultaneous atomic commit across Bukkit memory and PostgreSQL.

## Transfer contract

A transfer contains:

- internal payer `player_id`;
- internal payee `player_id`;
- normalized currency code;
- positive integer amount;
- reason;
- external idempotency key;
- correlation ID;
- JSON metadata.

The payer and payee must differ.

The base idempotency key is limited to 120 characters. The ledger derives:

- `<key>:debit`
- `<key>:credit`

Both use the same correlation ID. A separate `economy.transfer` audit event uses `transfer:<key>`.

## Lock ordering

Transfers may race in opposite directions. Locking payer first would produce this classic cycle:

```text
transaction A: lock Alice -> wait for Bob
transaction B: lock Bob   -> wait for Alice
```

The transfer primitive instead locks both account rows using a single query ordered by `player_id`. Both transactions therefore acquire the same logical order regardless of transfer direction.

## Replay semantics

A replay is successful only when **both** derived ledger entries already exist with the expected accounts, deltas and reason and belong to the same original correlation ID.

If only one side exists, or the key has different semantics, the operation fails closed with an idempotency conflict. It never tries to repair an ambiguous partial transfer by guessing.

A correctly replayed transfer returns the original debit/credit transaction IDs, original correlation ID and recorded post-transfer balances without moving money again.

## Phase 3C1 acceptance

Tests must prove:

- normal transfer debits and credits atomically;
- insufficient funds changes neither side;
- exact replay is a no-op and returns original transaction identity;
- the same key with a different amount/meaning fails closed;
- opposite-direction concurrent transfers do not deadlock;
- caller-composed transfers reject `autoCommit=true`;
- existing single-wallet economy behavior is unchanged;
- full Paper/crossplay stack still boots.

## Phase 3C2 planned state model

Exact names may change during implementation, but the durable workflow should express states equivalent to:

```text
LISTED
  -> RESERVED
  -> PAID
  -> DELIVERY_PENDING
  -> COMPLETED

RESERVED -> LISTED       (reservation expires before payment)
PAID/DELIVERY_PENDING    (recoverable; never silently cancelled)
```

A paid order must never return to a state that allows a second buyer to pay for the same item.

## Item delivery rules

Phase 3C2 must define an item snapshot/fingerprint and a delivery identifier. The delivery operation must be retry-safe.

Important distinction:

- database can prove whether payment settled;
- Bukkit can observe whether a player is currently online and has inventory capacity;
- neither fact alone proves an item was delivered exactly once.

The implementation therefore needs an explicit delivery acknowledgement/recovery mechanism. A paid-but-not-delivered order remains durable until delivery can be completed or staff recovery is deliberately invoked.

## Onboarding rule

`player_onboarding.market_used_at` is recorded only when the first market action reaches the terminal successful state. Reservation, payment attempt, or a paid-but-undelivered order is not onboarding completion.

## Non-goals for the MVP slice

Do not add yet:

- global auction search sophistication;
- bidding;
- offline mailboxes beyond what crash-safe delivery requires;
- price-history analytics UI;
- dynamic taxes/fees;
- cross-server distributed market services;
- Redis solely for market state;
- a Vault compatibility layer merely to reuse an external shop GUI.

The first goal is one understandable, recoverable trade path that cannot duplicate money or items.
