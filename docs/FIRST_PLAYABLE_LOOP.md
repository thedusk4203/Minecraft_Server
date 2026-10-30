# First playable loop — Phase 3

Phase 3 converts the durable platform into the first self-directed survival progression loop. Changes remain sliced so each dependency and invariant can be reviewed independently.

## Phase 3A — onboarding and land safety

The first player objective is intentionally simple:

1. join the server;
2. receive an onboarding objective;
3. create a protected land claim;
4. persist the claim milestone;
5. advance toward earning the first coin.

Onboarding state is keyed by internal `player_id`, not by player name or external platform identifiers. Milestones are represented as timestamps so later phases can extend the sequence without rewriting an enum contract.

### Land protection dependency

Phase 3A pins **GriefPrevention 16.18.7**.

Reasons:
- mature survival-oriented claim workflow;
- explicit Paper/Minecraft 26.3 compatibility for the pinned release;
- stable Bukkit event API for observing successful claims;
- Java and Bedrock players use the same server-side claim rules;
- the core only observes `ClaimCreatedEvent` and does not copy GriefPrevention ownership data into a competing claim system.

The dependency is pinned in both the build API dependency and local runtime configuration. Do not replace it with an unpinned `latest` download.

`MinecraftServerCore` depends on GriefPrevention for Phase 3A. If land protection is unavailable, the playable-loop core must not pretend the onboarding claim objective can be completed.

## Threading boundary

Bukkit/GriefPrevention event state is captured on the owning server thread. PostgreSQL writes are dispatched through `PersistenceExecutor`; no onboarding JDBC work runs on the Minecraft main thread.

Completion messages are scheduled back onto the Bukkit main thread and resolve the current online player by UUID rather than retaining a live `Player` object across asynchronous work.

## Durable onboarding contract

`player_onboarding` is a one-row-per-player progress record. Phase 3A uses:

- `started_at`
- `land_claimed_at`

Future slices reserve:

- `first_currency_earned_at`
- `market_used_at`
- `completed_at`

Required invariants:
- creating onboarding twice is a no-op;
- the first land-claim timestamp is immutable through normal replay;
- claim handling is robust even if the join bootstrap write has not completed yet;
- restart/reconnect preserves progress.

## Next dependency candidates

### Skills/jobs

**AuraSkills 2.4.0** is the current candidate for the next slice because its current release line supports Minecraft/Paper 26.3 and includes job-selection capabilities. It is not part of Phase 3A and must be pinned only when that slice is implemented and tested.

### Trading/market

Do not adopt a market plugin solely because it supports Vault economy calls. The core economy is PostgreSQL-authoritative and its JDBC operations must not be forced into synchronous main-thread callbacks.

QuickShop-Hikari was evaluated as a compatibility candidate, but its Vault-style economy integration does not fit the current threading/durability boundary cleanly. The default direction for the market slice is therefore a small server-owned async listing/escrow flow backed by the existing economy ledger unless a dependency proves it can preserve the same idempotency and threading guarantees.

## Phase 3A acceptance

Phase 3A is complete only when:
- the V2 migration passes against PostgreSQL 18;
- a first join starts onboarding exactly once;
- a successful non-cancelled GriefPrevention claim records `land_claimed_at` exactly once;
- restart preserves onboarding state;
- the full Docker stack boots with GriefPrevention loaded;
- existing identity, economy, admin, Velocity and crossplay smoke tests remain green.
