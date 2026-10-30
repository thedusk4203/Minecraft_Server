# First playable loop — Phase 3

Phase 3 converts the durable platform into the first self-directed survival progression loop. Changes remain sliced so each dependency and invariant can be reviewed independently.

## Phase 3A — onboarding and land safety

Status: complete

The first player objective is intentionally simple:

1. join the server;
2. receive an onboarding objective;
3. create a protected land claim;
4. persist the claim milestone;
5. advance toward skill progression and the first coin.

Phase 3A pins **GriefPrevention 16.18.7**. GriefPrevention remains authoritative for claim geometry and ownership; the core observes successful `ClaimCreatedEvent` events only to advance onboarding.

## Phase 3B — first skill level and starter currency

Status: complete

Phase 3B pins **AuraSkills 2.4.0**. AuraSkills supplies the skill progression signal; it does not become the authoritative economy.

Flow:

1. the player performs ordinary survival activities that grant AuraSkills XP;
2. AuraSkills emits `SkillLevelUpEvent`;
3. core records the first skill ID, level and timestamp;
4. core grants a one-time `100 COINS` onboarding reward through `EconomyLedger`;
5. the skill milestone, economy account mutation, immutable ledger row, economy audit and `first_currency_earned_at` milestone commit atomically;
6. replayed or concurrent level-up handling does not double-pay or replace the first skill milestone.

The deterministic reward idempotency key is:

`onboarding:first-currency:<player_id>`

The `100 COINS` amount is a small teaching grant for the onboarding loop, not a recurring income faucet and not a final economy-balance decision.

Phase 3B merged in PR #5 at `bb28cbf133fc0a4ef203a1147ce9402b440d5249`; final-head CI `41002439944` passed.

### Why Vault money rewards are disabled

AuraSkills can reward money through Vault, but Phase 3B intentionally does not use that capability. The server economy is PostgreSQL-authoritative and requires ledger idempotency, correlation IDs and no ordinary JDBC on the Minecraft main thread. Money enters the economy only through core-owned transaction logic.

### Atomicity boundary

`EconomyLedger.applyInTransaction` reuses the existing row-lock, overflow, negative-balance, idempotency and audit logic inside a transaction owned by a higher-level workflow. It never commits or rolls back the caller's connection.

`FirstSkillCurrencyService` locks the player's onboarding row before applying the deterministic economy mutation. If any ledger write fails, the first-skill and first-currency onboarding changes roll back with it.

## Phase 3C — first trade / market

Status: in progress

Phase 3C is split into two slices because Minecraft inventory state and PostgreSQL state do not share a transaction manager. A synchronous Vault bridge would not solve that failure boundary and would violate the existing no-main-thread-JDBC rule.

### Phase 3C1 — atomic payment settlement

The first slice adds a server-owned two-wallet settlement primitive:

1. validate payer, payee, currency, amount and idempotency key;
2. ensure both wallet rows exist;
3. lock both rows in deterministic internal-player UUID order;
4. verify the transfer has not already settled;
5. debit the payer and credit the payee inside one PostgreSQL transaction;
6. append the normal immutable `economy.balance_changed` audit for each side;
7. append an `economy.transfer` audit tying both ledger transactions to one correlation ID;
8. commit once.

A transfer uses one external idempotency key and derives two ledger keys:

- `<transfer-key>:debit`
- `<transfer-key>:credit`

The base key is limited to 120 characters so the derived keys remain inside the existing 128-character `EconomyMutation` contract. Transfer-level audit keys use `transfer:<transfer-key>` and remain inside the audit schema limit.

This slice deliberately does **not** mutate inventory or mark `market_used_at`. Passing Phase 3C1 proves only that money settlement is safe enough to compose into a later market state machine.

### Phase 3C2 — listing, escrow and delivery

The next slice will add durable market state. The implementation must treat item delivery as a retryable workflow rather than pretending it can commit atomically with PostgreSQL.

Required direction:

- durable listing/order identity;
- immutable item snapshot/fingerprint sufficient to validate the intended item;
- explicit lifecycle states for reservation, payment and delivery;
- payment through the Phase 3C1 transfer primitive;
- delivery acknowledgement before the order becomes terminal;
- reconnect/restart recovery for paid-but-not-delivered orders;
- no double delivery on retry;
- `market_used_at` recorded only after the first completed market action.

The smallest usable surface is preferred over a large auction-house feature set. Java and Bedrock players must both be able to understand and execute the flow without a required client mod.

## Threading boundary

Bukkit, GriefPrevention and AuraSkills event state is captured on the owning server thread. PostgreSQL writes are dispatched through `PersistenceExecutor`; no onboarding/economy JDBC work runs on the Minecraft main thread.

Future market item capture/removal/delivery must occur on the Bukkit owning thread. Database reservation/payment/recovery work must remain off-thread, with explicit state transitions connecting the two sides.

## Durable onboarding contract

`player_onboarding` is a one-row-per-player progress record keyed by internal `player_id`.

Current milestones:

- `started_at`
- `land_claimed_at`
- `first_skill_level_at`
- `first_skill_id`
- `first_skill_level`
- `first_currency_earned_at`

Reserved for the remaining loop:

- `market_used_at`
- `completed_at`

Required invariants:
- creating onboarding twice is a no-op;
- the first land-claim timestamp is immutable through normal replay;
- the first skill ID/level is immutable through normal replay;
- the first-currency reward is globally idempotent for that player;
- skill/currency milestone and ledger write are atomic;
- restart/reconnect preserves progress and balance;
- `market_used_at` must not be marked merely because payment was attempted.

## Reconnect guidance

Guidance is derived from durable state rather than a session-only stage:

- no land claim → claim objective;
- land claimed but no first currency → AuraSkills level-up objective and `/skills` hint;
- land + skill/currency complete but no market action → trading objective once Phase 3C2 is available;
- market complete → onboarding can advance to its completion state.

Out-of-order activity must remain safe. A skill can level before land is claimed, and future market recovery must tolerate disconnects between inventory and database transitions without duplicating value.
