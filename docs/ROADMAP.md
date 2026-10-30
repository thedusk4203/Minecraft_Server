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
Status: complete / frozen

Scope:
- one data-driven `stone_warden` boss definition consuming the frozen 4A runtime
- server-owned spawn/despawn and encounter-zone lifecycle
- participant eligibility bound to one durable encounter run
- player-readable Seismic Slam and Stone Charge telegraphs
- low-health enrage behavior
- horizontal and vertical anti-cheese boundaries
- death, disconnect, timeout, wipe/failure and spawn-failure behavior
- clean reset/despawn behavior
- telemetry for attempt start, mechanics and terminal outcome
- repeatable solo and two-client real-network-client E2E verification

Exit gate met:
- a real client can join the isolated Paper 26.3 verification stack through pinned ViaProxy translation;
- `/boss start` as a normal player creates one durable run before boss spawn;
- Stone Warden is visible to the client and remains bound to that run;
- the client remains connected through the mechanic window;
- Seismic Slam and Stone Charge telemetry are observed;
- only bound, arena-valid participant damage is accepted;
- client-originated melee damage completes the boss encounter;
- a two-client party can join, converge through normal client movement and start one Stone Warden run;
- both clients remain bound through mechanics and exactly two durable `encounter_participant` rows are persisted;
- latest durable solo and party runs reach `COMPLETED` through the same encounter lifecycle;
- restart, cleanup and terminalization continue to rely on the frozen Phase 4A durable authority;
- no reward/item/economy mutation exists in Phase 4B;
- existing Phase 1–4A integration/full-stack/market/crossplay regression gates remain green.

Receipts:
- PR #13 implementation merged at `014bf43779e12456c8f211d760d696411f097397`.
- Implementation final-head CI `41025886647` succeeded.
- PR #14 solo real-client verification merged at `781a7252c57829d5e2da7daeee8bf0ea6cef7f4e`.
- Solo verification exact head `adcc25866bd498506b7bf6afbf77111454fad5fc`.
- Solo verification CI `41028891703` succeeded.
- PR #15 two-client party verification merged at `9bd8eb5c0d345388ca0f12c4408ab544fe10ec58`.
- Party verification exact head `c4cc1ee718f9b14e08c1c8ce551af14b21bd1186`.
- Party verification CI `41031029381` succeeded.
- Freeze receipt: `docs/PHASE4B_FREEZE.md`.

### Phase 4C — Boss Reward Delivery
Status: in progress

Scope:
- deterministic reward eligibility derived only from a completed durable boss run;
- durable reward intent/history per eligible participant;
- deterministic reward identity so retries cannot create a second grant;
- auditable currency reward delivery through the existing economy ledger;
- retry-safe item reward delivery with explicit playerdata/DB ordering and recovery markers;
- restart/reconnect/manual recovery for interrupted delivery;
- reward telemetry and admin diagnostics;
- failure-injection tests around every transaction boundary;
- no dungeon expansion until reward delivery is proven.

Exit gate:
- one completed Stone Warden run produces exactly one reward intent per eligible participant;
- repeated finalization/recovery cannot mint a second currency or item reward;
- an incomplete/failed/abandoned encounter cannot produce a reward;
- currency reward ledger mutation and durable reward state remain transactionally consistent;
- item reward delivery survives disconnect/restart without intentionally duplicating or losing the reward;
- concurrent/replayed reward delivery serializes safely;
- reward audit and telemetry identify run, participant, reward definition/version and terminal delivery state;
- real-client boss E2E can continue through reward completion without regressing combat or existing Phase 1–4B gates.

#### Phase 4C1 — Durable Stone Warden Currency Rewards
Status: complete / frozen

Scope:
- PostgreSQL V7 `encounter_reward` state;
- concrete `stone_warden_completion` reward definition version 1;
- 250 `COINS` per canonical participant of a durable completed Stone Warden run;
- unique durable reward identity per `(run, player, reward key, reward version)`;
- atomic economy ledger + reward acknowledgement + audit transaction;
- deterministic economy idempotency from durable reward identity;
- startup/periodic reconciliation for the completion-to-reward gap;
- replay, concurrency and injected rollback verification;
- solo and two-client real-network-client reward E2E;
- no item/inventory mutation.

Receipts:
- PR #17 merged at `d34b3303f6322a84fc213a1b40de5f442956d7d8`.
- Implementation final head `9d3d678b811759d932449ad0a273cacd0742f10e`.
- Implementation CI `41039063048` succeeded.
- PR #18 E2E hardening merged at `aa956583862fab7705db93eee917c79938c355f2`.
- E2E-hardening CI `41051311119` succeeded.
- Frozen merge-state CI `41055264861` succeeded.
- Freeze receipt: `docs/PHASE4C1_FREEZE.md`.

#### Phase 4C2 — Crash-safe Stone Warden Item Reward Delivery
Status: next

Scope:
- preserve the frozen Phase 4C1 currency contract unchanged;
- define one concrete deterministic Stone Warden item reward rather than a generic/random loot catalog;
- persist a durable item-delivery identity and lifecycle subordinate to the existing reward identity;
- use a stable PDC delivery marker so recovery can distinguish an already persisted pending item from a duplicate mint;
- persist the exact marked item snapshot and integrity metadata before inventory delivery;
- persist playerdata before acknowledging terminal item delivery in PostgreSQL;
- protect pending marked reward items from movement/use/drop/death loss until delivery is acknowledged;
- reconnect/respawn/automatic/manual recovery for interrupted delivery;
- fail closed on duplicate markers, payload/hash mismatch, ownership mismatch or semantic drift;
- expose pending/completed reward-delivery state through telemetry and admin diagnostics;
- add concurrency and failure-injection integration coverage around item-state transitions;
- extend solo and two-client real-client Stone Warden E2E through item receipt and durable delivery acknowledgement.

Exit gate:
- every eligible completed Stone Warden participant receives at most one item for the frozen item definition/version;
- repeated recovery never intentionally mints a second item;
- crash after marked-playerdata persistence but before DB acknowledgement reuses the existing marker;
- crash after DB acknowledgement but before marker cleanup strips the stale marker without granting another item;
- full inventory leaves a recoverable pending delivery and does not discard the reward;
- non-completed runs and players outside the canonical participant set cannot obtain an item intent;
- all Phase 1–4C1 regression gates remain green.

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
Implement **Phase 4C2 — Crash-safe Stone Warden Item Reward Delivery** as a separate reviewable change-set consuming the frozen Phase 4A/4B/4C1 contracts.

Acceptance checklist:
- do not change the frozen 250-COINS Phase 4C1 grant semantics;
- define one concrete deterministic item key/version for Stone Warden completion;
- bind one durable item-delivery identity to each eligible existing `encounter_reward` identity;
- prepare durable item delivery before mutating Bukkit inventory;
- use a stable marker/identity so reconnect/restart can recognize an already persisted pending item instead of minting another;
- persist an integrity-checked marked item snapshot before delivery;
- persist playerdata before terminal database delivery acknowledgement;
- keep unresolved marked items protected from movement/use/drop/death loss;
- add automatic reconnect/respawn recovery plus an explicit manual recovery command;
- treat duplicate markers, ownership mismatch and payload/hash mismatch as fail-closed states requiring diagnostics;
- add concurrency and final-boundary failure-injection integration tests;
- extend admin diagnostics and reward telemetry with item-delivery state;
- extend solo and two-client real-client E2E through item receipt and durable acknowledgement;
- keep the market recovery contract and all existing regression gates green;
- do not add dungeon content until Phase 4C2 is frozen.
