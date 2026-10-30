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

Status: in progress

Phase 3B pins **AuraSkills 2.4.0**, whose release line supports Minecraft/Paper 26.3. AuraSkills supplies the skill progression signal; it does not become the authoritative economy.

Flow:

1. the player performs ordinary survival activities that grant AuraSkills XP;
2. AuraSkills emits `SkillLevelUpEvent`;
3. core records the first skill ID, level and timestamp;
4. core grants a one-time `100 COINS` onboarding reward through `EconomyLedger`;
5. the skill milestone, economy account mutation, immutable ledger row, economy audit and `first_currency_earned_at` milestone commit atomically;
6. replayed or concurrent level-up handling does not double-pay or replace the first skill milestone.

The deterministic reward idempotency key is:

`onboarding:first-currency:<player_id>`

The `100 COINS` amount is a small teaching grant for the onboarding loop, not a recurring income faucet and not a claim about final economy balance.

### Why Vault money rewards are disabled

AuraSkills 2.4.0 can reward money through Vault, but Phase 3B intentionally does not use that capability. The server economy is PostgreSQL-authoritative and requires ledger idempotency, correlation IDs and no ordinary JDBC on the Minecraft main thread. Money enters the economy only through core-owned transaction logic.

### Atomicity boundary

`EconomyLedger.applyInTransaction` reuses the existing row-lock, overflow, negative-balance, idempotency and audit logic inside a transaction owned by a higher-level workflow. It never commits or rolls back the caller's connection.

`FirstSkillCurrencyService` locks the player's onboarding row before applying the deterministic economy mutation. If any ledger write fails, the first-skill and first-currency onboarding changes roll back with it. Integration tests deliberately inject an idempotency conflict to verify this behavior.

## Threading boundary

Bukkit, GriefPrevention and AuraSkills event state is captured on the owning server thread. PostgreSQL writes are dispatched through `PersistenceExecutor`; no onboarding/economy JDBC work runs on the Minecraft main thread.

Completion messages are scheduled back onto the Bukkit main thread and resolve the current online player by UUID rather than retaining a live `Player` object across asynchronous work.

## Durable onboarding contract

`player_onboarding` is a one-row-per-player progress record keyed by internal `player_id`.

Current milestones:

- `started_at`
- `land_claimed_at`
- `first_skill_level_at`
- `first_skill_id`
- `first_skill_level`
- `first_currency_earned_at`

Reserved for later slices:

- `market_used_at`
- `completed_at`

Required invariants:
- creating onboarding twice is a no-op;
- the first land-claim timestamp is immutable through normal replay;
- the first skill ID/level is immutable through normal replay;
- the first-currency reward is globally idempotent for that player;
- skill/currency milestone and ledger write are atomic;
- restart/reconnect preserves progress and balance.

## Reconnect guidance

Guidance is derived from durable state rather than a session-only stage:

- no land claim → claim objective;
- land claimed but no first currency → AuraSkills level-up objective and `/skills` hint;
- land + skill/currency complete → current implemented onboarding steps are complete and the player can continue gathering resources until the trading slice arrives.

This also handles out-of-order play: if a player levels a skill before claiming land, the reward is still recorded exactly once and the land objective remains the visible blocker until completed.

## Phase 3C direction — trading/market

Do not adopt a market plugin solely because it supports Vault economy calls. The core economy is PostgreSQL-authoritative and its JDBC operations must not be forced into synchronous main-thread callbacks.

QuickShop-Hikari was evaluated as a compatibility candidate, but its Vault-style economy boundary does not fit the current threading/durability contract cleanly. The default direction is a small server-owned async listing/escrow flow backed by the existing economy ledger unless a dependency proves it can preserve the same idempotency and transaction guarantees.

## Phase 3B acceptance

Phase 3B is complete only when:
- the V3 migration passes against PostgreSQL 18;
- AuraSkills 2.4.0 compiles and loads with Paper 26.3;
- the first skill level is persisted with skill ID + level;
- the onboarding reward is paid exactly once;
- an induced economy conflict proves skill/currency milestone rollback;
- restart preserves land, skill, currency milestone and balance;
- full Docker stack remains green with GriefPrevention, AuraSkills, Velocity, Geyser and Floodgate.
