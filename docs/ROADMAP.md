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
Status: in progress

Deliverables:
- Onboarding
- Land protection integration
- Currency loop
- Skills/jobs baseline
- Trading/market baseline
- Starter objectives/quests

Exit gate:
- New player can join, understand the objective, claim land, earn currency and complete an initial progression loop without staff intervention.

### Phase 3A — Onboarding + first land claim
Status: complete

Scope:
- durable onboarding progress tied to internal `player_id`
- first-join objective guidance
- GriefPrevention 16.18.7 land protection
- first successful claim recorded as a durable milestone
- onboarding state survives restart/reconnect

Exit gate met:
- A new player receives the first objective without staff intervention.
- Creating the first non-cancelled GriefPrevention claim records `land_claimed_at` exactly once.
- Repeated joins/claims are idempotent.
- Restart preserves the onboarding milestone.

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
Status: in progress

The market work is split because Bukkit inventory and PostgreSQL do not share a transaction manager. Money settlement is atomic in PostgreSQL; item movement uses a durable recovery state machine.

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
Status: in progress

Scope:
- PostgreSQL V4 durable listing/escrow state
- raw-NBT `ItemStack` snapshot with SHA-256 integrity metadata
- PDC escrow token written to the live item before database preparation
- crash-ordering: marker persisted before `PREPARED`, inventory removal persisted before `LISTED`
- escrow-marked items cannot be moved, dropped, used, damaged, mended or lost on death
- seller cancellation uses `LISTED -> RETURN_PENDING -> CANCELLED`
- returned item is persisted to playerdata before terminal `CANCELLED`
- reconnect and `/market recover` reconcile interrupted prepare/activate/cancel flows
- minimal `/market sell`, `/market list`, `/market cancel`, `/market recover` surface
- no buyer payment and no `market_used_at` in this slice

Exit gate:
- Seller can escrow the held stack and obtain a durable `LISTED` listing.
- `PREPARED` retry by escrow token cannot create a duplicate listing.
- Wrong-owner cancellation cannot expose or return another seller's item.
- Crash/reconnect recovery can finish PREPARED activation or RETURN_PENDING cancellation without duplicating or losing the item.
- Listing snapshot and state survive database restart.
- Full stack loads V4 and `/market` command wiring while existing gameplay/crossplay smoke remains green.

#### Phase 3C2b — Purchase settlement + buyer delivery + first trade
Status: planned

Scope direction:
- reserve an active listing for one buyer
- settle payment through the Phase 3C1 atomic transfer primitive
- durable paid/delivery-pending state
- retry-safe buyer item delivery with explicit acknowledgement
- first completed market action records `market_used_at` exactly once
- onboarding advances only after delivery reaches terminal success

Exit gate:
- A player with onboarding currency can buy one listing without staff intervention.
- Payment is charged exactly once and seller is credited exactly once.
- Crash/retry cannot duplicate the purchased item or charge twice.
- `market_used_at` is written only after completed delivery.

## Phase 4 — PvE content
Deliverables:
- Encounter framework
- First boss
- First dungeon/equivalent structured PvE activity
- Reward tables
- Encounter telemetry

Exit gate:
- A small party can complete the full PvE loop repeatedly without duplication/data-loss bugs.

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
Finish Phase 3C2a and prove seller escrow recovery before implementing buyer settlement.

Acceptance checklist:
- V4 migrates on PostgreSQL 18;
- PREPARED creation is idempotent by escrow token;
- item snapshot stores raw NBT bytes plus integrity hash;
- escrow marker is durable before PREPARED becomes durable;
- inventory removal is durable before LISTED becomes durable;
- return delivery is durable before CANCELLED becomes durable;
- marked items are protected from player mutation/drop/death loss;
- wrong-owner cancellation fails closed;
- repository state transitions and restart persistence are covered by integration tests;
- `/market` is registered in the full Paper stack;
- all existing economy/onboarding/crossplay tests remain green.
