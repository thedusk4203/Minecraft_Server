# First playable loop — Phase 3

Phase 3 converts the durable platform into the first self-directed survival progression loop. Changes remain sliced so each dependency and invariant can be reviewed independently.

## Phase 3A — onboarding and land safety

Status: complete

The first objective is to claim land with GriefPrevention 16.18.7. GriefPrevention remains authoritative for claim geometry and ownership; core only records the first successful non-cancelled claim as durable onboarding progress.

## Phase 3B — first skill level and starter currency

Status: complete

AuraSkills 2.4.0 supplies the progression signal; it does not become the authoritative economy. The first observed skill level records skill ID/level and atomically grants one `100 COINS` teaching reward through the PostgreSQL ledger.

The deterministic reward key is:

`onboarding:first-currency:<player_id>`

Phase 3B merged in PR #5 at `bb28cbf133fc0a4ef203a1147ce9402b440d5249`; final-head CI `41002439944` passed.

## Phase 3C — first trade / market

Status: in progress

Minecraft inventory and PostgreSQL do not share a transaction manager. The market therefore separates two correctness problems:

- money ↔ money uses one PostgreSQL transaction;
- item ↔ durable market state uses token-based, restart-safe sagas around playerdata saves.

### Phase 3C1 — atomic payment settlement

Status: complete

`EconomyTransfer` creates/locks both wallet rows in deterministic internal-player order, writes payer debit + payee credit, appends audits and commits once. Exact replay returns the original ledger identity without moving money twice.

PR #6 merged at `96f756f5e20caaf1151d484110c40e2b1bcfa243`; final-head CI `41003473192` passed.

### Phase 3C2a — seller escrow and listing

Status: complete

The seller flow introduces V4 `market_listing` and an escrow PDC UUID stored inside the raw-NBT item snapshot.

Crash ordering:

```text
mark held item
  -> Player.saveData()
  -> PREPARED
  -> remove marked item
  -> Player.saveData()
  -> LISTED
```

Cancellation is similarly ordered:

```text
LISTED -> RETURN_PENDING
  -> reconstruct marked snapshot in seller inventory
  -> Player.saveData()
  -> CANCELLED
  -> strip marker best effort
```

The escrow guard prevents a marked item from being moved, dropped, used, damaged or lost on death while recovery is unresolved.

PR #7 merged at `8ccfa391cdc1ef86194b9795f4fff3066d7d4b41`; final-head CI `41013473040` passed.

### Phase 3C2b — buyer payment and delivery

Status: in progress

V5 adds one durable `market_purchase` row per sold listing. Payment and listing reservation share a PostgreSQL transaction:

```text
lock LISTED row
  -> transfer buyer -> seller with market:purchase:<listing_id>
  -> insert market_purchase DELIVERY_PENDING
  -> listing LISTED -> DELIVERY_PENDING
  -> audit purchase payment
  -> commit once
```

A second buyer serializes on the listing row and cannot pay once it leaves LISTED. Exact retry by the winning buyer reuses the deterministic transfer key and verifies the original debit/credit transaction IDs.

Buyer delivery reuses the seller escrow token as an exactly-once acknowledgement marker:

```text
if token not already in buyer inventory:
  verify SHA-256 + raw-NBT escrow token
  place marked snapshot in one empty slot
Player.saveData()                    # item durable before acknowledgement

purchase/listing DELIVERY_PENDING -> COMPLETED
mark buyer + seller market_used_at
append completion audit
commit

strip marker
Player.saveData() best effort
```

This ordering handles all important restart windows:

- crash before the item save: delivery remains pending and can safely mint the snapshot later;
- crash after item save but before DB completion: recovery sees the token and acknowledges the existing item instead of creating another;
- crash after DB completion but before marker cleanup: recovery strips the stale token;
- inventory full: the paid purchase remains DELIVERY_PENDING until an empty slot exists.

Seller recovery treats tokens belonging to another listing owner as protected because the token may legitimately be the buyer's delivery marker. Buyer recovery separately verifies `market_purchase.buyer_player_id` before acknowledging or cleaning it.

## Threading boundary

Bukkit inventory access, item snapshot placement/removal and `Player.saveData()` run on the server-owning thread. PostgreSQL work runs on `PersistenceExecutor`; ordinary JDBC never runs on the Minecraft main thread.

`Player.saveData()` is deliberately limited to escrow/delivery boundaries where durable playerdata ordering is necessary. It is not a general save strategy.

## Durable onboarding contract

`player_onboarding` remains one row per internal `player_id`.

Current progression signals:

- `started_at`
- `land_claimed_at`
- `first_skill_level_at`
- `first_skill_id`
- `first_skill_level`
- `first_currency_earned_at`
- `market_used_at`

`completed_at` remains reserved for a later explicit completion policy.

`market_used_at` is **not** written when a listing is created or payment merely succeeds. It is written for both buyer and seller only when delivery reaches terminal `COMPLETED` in the same database transaction as purchase/listing completion.

## Player guidance

Guidance comes from durable state:

- no land claim → create a protected claim;
- land but no starter currency → level AuraSkills, with `/skills` hint;
- starter currency but no market milestone → `/market list`, `/market buy <#>`, or `/market sell <price>`;
- market milestone present → the current onboarding loop is complete.

The resulting intended player loop is:

```text
join
  -> claim land
  -> level a skill
  -> receive starter COINS
  -> inspect/use market
  -> complete first trade
```

No staff intervention is required in the normal path; disconnect/restart recovery remains explicit through reconnect handling and `/market recover`.
