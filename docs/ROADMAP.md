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
Status: in progress

Scope:
- AuraSkills 2.4.0 pinned for Paper/Minecraft 26.3
- first skill ID/level/timestamp persisted durably
- one-time 100 COINS teaching grant
- onboarding skill/currency milestone and economy ledger mutation committed atomically
- deterministic per-player idempotency key
- reconnect guidance derived from durable onboarding state

Exit gate:
- First AuraSkills level-up records the first skill exactly once.
- Starter currency is paid exactly once through the core economy ledger.
- Concurrent/replayed level-up handling cannot double-pay or replace first-skill telemetry.
- A forced ledger conflict rolls back both skill and currency milestones.
- Restart preserves land, skill, currency and balance state.
- Full stack loads both GriefPrevention and AuraSkills while existing crossplay/core smoke tests remain green.

### Phase 3C — First trade / market checkpoint
Status: planned

Scope direction:
- server-owned async listing/escrow workflow unless an external dependency proves compatible with the PostgreSQL/idempotency/threading contract
- first successful buy/sell/trade records `market_used_at`
- trade settlement uses compensating/auditable ledger operations rather than direct balance edits
- starter objective advances without staff intervention

Exit gate:
- A player with onboarding currency can complete one market/trade action.
- Settlement is duplication-safe and survives retry/restart.
- Onboarding records `market_used_at` exactly once.

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
Finish Phase 3B: first skill level and starter currency.

Acceptance checklist:
- `V3__first_skill_progress.sql` migrates on PostgreSQL 18.
- AuraSkills 2.4.0 API compiles and the plugin loads in the Paper stack.
- `SkillLevelUpEvent` persistence runs outside the Minecraft main thread.
- First skill ID/level is immutable through replay.
- The 100 COINS onboarding grant is globally idempotent per player.
- Ledger + first-currency milestone are one database transaction.
- Conflict rollback and restart persistence are covered by integration tests.
- Full-stack CI remains green.
