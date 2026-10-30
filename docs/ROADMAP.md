# Roadmap — MVP v0.1

## Phase 0 — Product foundation
Status: complete

Deliverables:
- Product specification
- Game design foundation
- Technical architecture
- Agent operating rules
- MVP acceptance criteria

Exit gate:
- Scope is narrow enough that an implementation agent can work without inventing product direction.

## Phase 1 — Local technical skeleton
Status: complete

Deliverables:
- Repository layout
- Paper-compatible plugin project
- Local development environment
- PostgreSQL development database
- Java + Bedrock crossplay proof
- Basic logging and health diagnostics
- CI build/test workflow

Exit gate:
- A clean checkout can be built and a local test server can be started reproducibly.
- Java and Bedrock test clients can join.

## Phase 2 — Core platform
Status: complete

Deliverables:
- Player identity abstraction
- Persistence layer and migrations
- Audit/event logging
- Permissions integration
- Economy transaction ledger
- Admin diagnostics

Exit gate:
- Restart/reconnect tests preserve player state.
- Economy mutations are auditable and duplication-safe.

## Phase 3 — First playable loop
Status: complete

Deliverables:
- Onboarding
- Land protection integration
- Currency loop
- Skills/jobs baseline
- Trading/market baseline
- Starter objectives/quests

Exit gate met:
- A new player can join, understand the current objective, claim land, earn starter currency and complete a first player-to-player trade without staff intervention.
- Durable onboarding state drives reconnect guidance.
- Money settlement is auditable and idempotent.
- Seller escrow and buyer delivery survive retry/restart without intentionally duplicating money or the escrowed item.
- Java and Bedrock share the same server-authoritative path.

Freeze receipt: `docs/PHASE3_FREEZE.md`.

### Phase 3A — Onboarding + first land claim
Status: complete

Scope:
- durable onboarding progress tied to internal `player_id`
- first-join objective guidance
- GriefPrevention 16.18.7 land protection
- first successful claim recorded as a durable milestone
- onboarding state survives restart/reconnect

Receipt:
- PR #4 merged at `eab159695157a811abb987e7f8a23e56bf9eac0c`.
- Final-head CI `41000886983` succeeded.

### Phase 3B — First skill level + starter currency
Status: complete

Scope:
- AuraSkills 2.4.0 pinned for Paper/Minecraft 26.3
- first skill ID/level/timestamp persisted durably
- one-time 100 COINS teaching grant
- onboarding skill/currency milestone and economy ledger mutation committed atomically
- deterministic per-player idempotency key
- reconnect guidance derived from durable onboarding state

Receipt:
- PR #5 merged at `bb28cbf133fc0a4ef203a1147ce9402b440d5249`.
- Final-head CI `41002439944` succeeded.

### Phase 3C — First trade / market checkpoint
Status: complete

The market work is split because Bukkit inventory and PostgreSQL do not share a transaction manager. Money settlement is atomic in PostgreSQL; item movement uses durable recovery state machines.

#### Phase 3C1 — Atomic P2P settlement foundation
Status: complete

Scope:
- server-owned transfer primitive for one currency between two internal players
- debit and credit ledger entries committed in one PostgreSQL transaction
- deterministic account-creation and row-lock ordering
- globally idempotent transfer replay
- transfer-level audit in addition to immutable balance-change audits
- insufficient-funds and idempotency-conflict paths fail closed

Receipt:
- PR #6 merged at `96f756f5e20caaf1151d484110c40e2b1bcfa243`.
- Final-head CI `41003473192` succeeded.

#### Phase 3C2a — Seller item escrow + listing
Status: complete

Scope:
- PostgreSQL V4 durable listing/escrow state
- raw-NBT `ItemStack` snapshot with SHA-256 integrity metadata
- PDC escrow token written to the live item before database preparation
- crash-ordering: marker persisted before `PREPARED`, inventory removal persisted before `LISTED`
- escrow-marked items cannot be moved, dropped, used, damaged, mended or lost on death
- seller cancellation uses `LISTED -> RETURN_PENDING -> CANCELLED`
- returned item is persisted to playerdata before terminal `CANCELLED`
- reconnect and `/market recover` reconcile interrupted prepare/activate/cancel flows

Receipt:
- PR #7 merged at `8ccfa391cdc1ef86194b9795f4fff3066d7d4b41`.
- Final-head CI `41013473040` succeeded.

#### Phase 3C2b — Purchase settlement + buyer delivery + first trade
Status: complete

Scope:
- PostgreSQL V5 `market_purchase` state separate from seller listing state
- one buyer serializes ownership of a `LISTED` row before payment can settle
- payment composes Phase 3C1 `EconomyTransfer` in the same transaction as `DELIVERY_PENDING`
- deterministic per-listing payment idempotency key prevents double charge
- buyer item delivery reuses the escrow token as an exactly-once recovery identity
- reconnect/respawn/manual recovery finishes interrupted delivery
- first completed market action records `market_used_at` for both buyer and seller

Receipt:
- PR #8 merged at `8657de3c1116e95f051f04f465d686f8d8c39790`.
- Final-head CI `41016015451` succeeded.
- Merge-state CI `41017540269` succeeded.

## Phase 4 — PvE content
Status: in progress

Deliverables:
- Encounter framework
- First boss
- First dungeon/equivalent structured PvE activity
- Reward tables
- Encounter telemetry

Exit gate:
- A small party can complete the full PvE loop repeatedly without duplication/data-loss bugs.

### Phase 4A — Encounter Runtime Foundation
Status: implemented in PR #10 — pending final CI, merge and freeze

Implemented scope:
- V6 durable `encounter_run` and `encounter_participant` state
- resource-driven encounter definitions with strict startup validation
- canonical semantic SHA-256 definition fingerprints stored with every run
- explicit lifecycle `ACTIVE -> COMPLETED | FAILED | ABANDONED`
- start idempotency keyed by a globally unique correlation ID
- canonical participant membership persistence
- row-locked terminal transitions and one terminal audit per run
- startup recovery that converts stale `ACTIVE` rows to `ABANDONED / SERVER_RESTART`
- encounter attempt/completion/failure/abandonment audit telemetry
- `/mcserver diagnostics` total/active encounter run visibility
- Flyway/Paper classloader hardening plus shaded-JAR migration/resource verification
- unit and PostgreSQL 18 integration tests for validation, replay, concurrency, restart and failure-injection rollback

Non-goals for 4A:
- first boss mechanics
- mob spawning
- dungeon layout/content
- reward economy or loot delivery
- matchmaking/party-system expansion
- player-facing encounter UI

Exit gate pending freeze:
- one synthetic encounter definition starts and terminates a durable run deterministically;
- invalid definitions fail startup/validation closed;
- duplicate/replayed lifecycle commands cannot create conflicting terminal outcomes;
- concurrent conflicting terminal transitions serialize to one winner;
- unresolved runs receive explicit restart semantics instead of pretending world state survived;
- run/participant telemetry is queryable and auditable;
- transaction-tail audit conflicts roll back preceding run state;
- V6 and encounter resources are present in the shaded runtime JAR;
- existing Phase 1–3 integration and full-stack/crossplay smoke remain green.

Implementation detail: `docs/ENCOUNTER_RUNTIME.md`.

## Phase 5 — Commerce and cosmetics
Deliverables:
- Entitlement model
- Cosmetic framework
- Supporter ranks
- Purchase delivery idempotency
- Audit trail
- Policy/compliance review

Exit gate:
- Test purchases can be delivered, retried and reconciled without duplicate grants.

## Phase 6 — Operations
Deliverables:
- Backups
- Restore drill
- Metrics/dashboard
- Alerting
- Moderation baseline
- Discord integration
- Staging deployment

Exit gate:
- Seven-day staging soak without critical manual intervention.

## Phase 7 — Closed alpha
Target:
- >= 20 invited testers

Validate:
- onboarding completion
- session length
- obvious economy exploits
- Java/Bedrock parity
- stability
- first retention signals

No feature expansion is allowed solely because testers request more content. Fix core-loop and reliability issues first.

## Phase 8 — Public MVP
First milestone:
- 20–30 stable concurrent players

Primary metrics:
- D1 / D7 retention
- onboarding completion
- average session duration
- return frequency
- economy inflation/sinks
- error/crash rate
- payer conversion and ARPPU

## Expansion gate
Do not add a second major game mode until:
- the existing mode has stable retention,
- operational burden is under control,
- player density can support splitting the population,
- and telemetry identifies a concrete opportunity.

Candidate expansion after the gate:
- Skyblock
- Prison
- additional survival shards/content

## Immediate next implementation task
Finish **Phase 4A — Encounter Runtime Foundation** on PR #10, require exact-head CI and merge-state CI to pass, then freeze 4A in a docs-only receipt before starting Phase 4B.

Phase 4B should be the first boss encounter consuming the frozen runtime. Boss mechanics, telegraphs, leash/anti-cheese rules and encounter completion/failure wiring should be reviewed separately from deterministic reward/loot delivery.
