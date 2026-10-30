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

Exit gate:
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

Exit gate met:
- First AuraSkills level-up records the first skill exactly once.
- Starter currency is paid exactly once through the core economy ledger.
- Concurrent/replayed level-up handling cannot double-pay or replace first-skill telemetry.
- A forced ledger conflict rolls back both skill and currency milestones.
- Restart preserves land, skill, currency and balance state.
- Full stack loads GriefPrevention and AuraSkills while existing crossplay/core smoke tests remain green.

Receipt:
- PR #5 merged at `bb28cbf133fc0a4ef203a1147ce9402b440d5249`.
- Final-head CI `41002439944` succeeded.

### Phase 3C — First trade / market checkpoint
Status: in progress

The market work is intentionally split because moving Minecraft inventory and PostgreSQL money cannot be made truly atomic by wrapping a synchronous Vault callback around JDBC.

#### Phase 3C1 — Atomic P2P settlement foundation
Status: in progress

Scope:
- server-owned transfer primitive for one currency between two internal players
- debit and credit ledger entries committed in one PostgreSQL transaction
- deterministic lock ordering for both wallet rows
- globally idempotent transfer replay
- explicit transfer-level audit event in addition to immutable balance-change audits
- insufficient-funds and idempotency-conflict paths fail closed
- no Vault bridge and no inventory mutation in this slice

Exit gate:
- Successful settlement debits buyer and credits seller atomically.
- Retrying the same settlement cannot move money twice.
- Opposite-direction concurrent settlements complete without a lock-order deadlock.
- Insufficient funds leaves both balances unchanged.
- Reusing a settlement key with different semantics fails closed.
- Existing Phase 2/3A/3B tests and full-stack smoke remain green.

#### Phase 3C2 — Durable listing / item escrow + first trade
Status: planned

Scope direction:
- durable listing/order state in PostgreSQL
- explicit crash-safe item handoff/claim state instead of pretending inventory and database are one transaction
- settlement uses the Phase 3C1 transfer primitive
- first completed market action records `market_used_at` exactly once
- reconnect/retry can recover an interrupted delivery without duplicating money or items
- minimal Java/Bedrock-friendly interaction surface

Exit gate:
- A player with onboarding currency can complete one buy/sell/trade action without staff intervention.
- Payment is duplication-safe and item delivery is retry/restart-safe.
- Onboarding records `market_used_at` exactly once.
- A crash/retry cannot duplicate the purchased item or charge twice.

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
Finish Phase 3C1 and prove the settlement primitive in CI before adding item/listing state.

Acceptance checklist:
- transfer contract rejects self-transfer, zero/negative amounts and oversized idempotency keys;
- both wallet rows are locked in deterministic order;
- debit + credit + audits commit or roll back together;
- transfer replay returns the original ledger transaction IDs and does not move money twice;
- insufficient funds and idempotency conflicts fail closed;
- concurrent opposite-direction transfers pass under PostgreSQL 18;
- all existing economy/onboarding integration tests remain green;
- full Docker stack remains green.
