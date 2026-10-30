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

Exit gate met:
- A new player receives the first objective without staff intervention.
- Creating the first non-cancelled GriefPrevention claim records `land_claimed_at` exactly once.
- Repeated joins/claims are idempotent.
- Restart preserves the onboarding milestone.

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
- minimal `/market sell`, `/market list`, `/market cancel`, `/market recover` surface

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
- marked item is persisted to playerdata before database delivery acknowledgement
- reconnect/respawn/manual recovery finishes interrupted delivery
- first completed market action records `market_used_at` for both buyer and seller
- onboarding guidance points directly to `/market list`, `/market buy`, and `/market sell`

Exit gate met:
- A player with onboarding currency can buy one listing without staff intervention.
- Payment is charged exactly once and seller is credited exactly once under replay.
- Two concurrent buyers cannot both acquire the same listing.
- Insufficient funds leaves the listing available and creates no purchase.
- Paid delivery survives disconnect/restart and reuses the marked item instead of intentionally minting a duplicate.
- `market_used_at` is written only after completed delivery.
- Final payment and completion transaction boundaries fail closed under injected audit conflicts.
- Full Paper/crossplay stack remains green after merge.

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
Status: complete / frozen

Scope:
- data-driven encounter definition validation
- durable encounter run identity and lifecycle
- participant tracking sufficient for later party encounters
- attempt/completion/failure telemetry
- restart-safe state handling
- audit/diagnostic visibility
- deterministic lifecycle/idempotency tests

Exit gate met:
- the synthetic `framework_probe` definition loads and validates deterministically;
- a durable run records encounter ID/version/fingerprint and canonical participants;
- exact start replay returns the existing run while semantic correlation reuse fails closed;
- conflicting terminal outcomes serialize and cannot both commit;
- lifecycle state and audit events share transaction boundaries and roll back together under injected failure;
- restart explicitly abandons stale `ACTIVE` runs with `SERVER_RESTART` before gameplay initialization completes;
- run/participant diagnostics are queryable;
- the shaded runtime JAR is checked for migration and encounter-definition resources;
- Paper/Flyway full-stack boot, existing gameplay dependencies, market wiring and crossplay smoke remain green.

Receipt:
- PR #10 merged at `f2f2d8704bb0cbfb542b0b04f64827e69f0b224c`.
- Final head `eeb21e40b95898ac44baf19cb4f3512c25571c91`.
- Final-head CI `41020474477` succeeded.
- Freeze receipt: `docs/PHASE4A_FREEZE.md`.

### Phase 4B — First Boss Combat Vertical Slice
Status: next

Scope:
- one data-driven boss definition consuming the frozen 4A runtime
- server-owned spawn/despawn and encounter-zone lifecycle
- participant eligibility bound to one durable encounter run
- clear player-readable telegraphs
- at least two mechanics beyond basic melee
- explicit anti-cheese boundaries
- death, disconnect, timeout and wipe/failure behavior
- clean reset/despawn behavior
- telemetry for attempts, participant deaths, duration and terminal outcome

Non-goals for 4B:
- durable loot/economy reward delivery
- first dungeon layout/content
- matchmaking or party-system expansion
- advanced boss authoring GUI
- broad content catalog

Exit gate:
- a small party can start the first boss from a validated definition and the world entity lifecycle is bound to exactly one durable encounter run;
- boss completion, wipe/failure, timeout and participant-loss paths converge on an explicit terminal run outcome;
- server restart cannot leave a boss entity treated as a live resumable run;
- at least two mechanics have readable telegraphs and deterministic state-machine boundaries;
- arena/zone rules block obvious trivial cheese without relying on staff intervention;
- repeated cleanup/despawn is safe and does not produce conflicting terminal outcomes;
- attempt/death/duration/outcome telemetry is observable;
- no reward is granted yet, so combat correctness can be reviewed independently from anti-duplication reward delivery;
- existing Phase 1–4A integration and full-stack smoke tests remain green.

### Phase 4C — Boss Reward Delivery
Status: planned

Scope direction:
- deterministic reward eligibility derived from a completed boss run;
- durable reward intent/history;
- retry-safe item/currency delivery with explicit anti-duplication boundaries;
- reward telemetry and audit;
- no dungeon expansion until reward delivery is proven.

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
Implement **Phase 4B — First Boss Combat Vertical Slice** as a separate reviewable change-set consuming the frozen 4A encounter runtime.

Acceptance checklist:
- add exactly one validated boss definition rather than a generic boss catalog;
- bridge boss spawn/despawn to one durable `encounter_run` identity;
- define explicit boss runtime states and permitted transitions;
- define arena/encounter-zone entry, boundary and cleanup rules;
- implement clear telegraphs and at least two mechanics beyond basic melee;
- define participant death, disconnect, wipe and timeout semantics;
- make terminalization idempotent under duplicate cleanup/events;
- record attempt/death/duration/outcome telemetry without inventing reward state;
- add deterministic unit/integration tests for state-machine and cleanup boundaries where Paper APIs permit;
- add full-stack smoke proving the boss runtime loads without regressing Phase 1–4A systems;
- do not implement loot/currency rewards until Phase 4C.
